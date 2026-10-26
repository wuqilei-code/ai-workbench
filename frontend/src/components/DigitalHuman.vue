<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useChatStore } from '@/stores/chat'
import { fileApi } from '@/api/files'
import { jobApi } from '@/api/jobs'
import baseImg from '@/assets/dh-body-base.webp'
import eyeLImg from '@/assets/dh-eye-l.webp'
import eyeRImg from '@/assets/dh-eye-r.webp'
import mouthImg from '@/assets/dh-mouth.webp'

/**
 * 数字人助手 · 无框悬浮形象版
 * - 点角色 → 头顶呈扇状展开动作按钮(说话/通话/朗读回复/打开浏览器/导出会话/音色),无面板框;
 * - 5 状态 + 点击反馈 + 待机随机小动作 + 眨眼;
 * - 语音:SpeechRecognition 提问、SpeechSynthesis 朗读(按句流式、可打断、可选音色/语速);
 * - 通话模式:免手连续对话;语音指令:本地关键词直接操作界面;一键任务:真执行。
 */
const { t } = useI18n()
const chat = useChatStore()
const sidebarEvents = inject<any>('sidebarEvents', null)

const open = ref(false)
const listening = ref(false)
const ttsSpeaking = ref(false)
const greetActive = ref(false)
const reacting = ref(false)
const voiceError = ref('')
const hintOn = ref(false)
const idleAction = ref('')
const callMode = ref(false)
const transient = ref('')
/** 自动朗读回复:默认关闭,仅用户显式开启后才逐句朗读新回复 */
const autoRead = ref(localStorage.getItem('dh_auto_read') === '1')
/** 是否存在可用的麦克风设备:无设备时麦克风/通话入口置灰不可用 */
const micAvailable = ref(true)

const SR = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
const micSupported = !!SR
const ttsSupported = typeof window !== 'undefined' && 'speechSynthesis' in window
const secureContext = typeof window !== 'undefined' ? window.isSecureContext : true

// ---------- 状态机 ----------
const state = computed<'idle' | 'greet' | 'listen' | 'think' | 'speak'>(() => {
  if (listening.value) return 'listen'
  if (greetActive.value) return 'greet'
  if (ttsSpeaking.value || (chat.isStreaming && !!chat.streamingText)) return 'speak'
  if (chat.isStreaming) return 'think'
  return 'idle'
})

const blink = ref(false)
let blinkTimer = 0
function scheduleBlink() {
  window.clearTimeout(blinkTimer)
  blinkTimer = window.setTimeout(() => {
    blink.value = true
    window.setTimeout(() => { blink.value = false }, 160)
    scheduleBlink()
  }, 2600 + Math.random() * 4200)
}

const lastAssistant = computed(() => {
  for (let i = chat.messages.length - 1; i >= 0; i--) {
    if (chat.messages[i].role === 'assistant') return chat.messages[i]
  }
  return null
})
function clip(s: string, n = 240): string {
  const x = (s || '').trim()
  return x.length > n ? x.slice(0, n) + '…' : x
}

const statusText = computed(() => {
  if (voiceError.value) return voiceError.value
  if (transient.value) return transient.value
  if (greetActive.value) return t('human.greetSay')
  if (listening.value) return t('human.listenSay')
  if (chat.isStreaming) return clip(chat.streamingText, 120) || t('human.thinkSay')
  return ''
})
let transientTimer = 0
function flash(msg: string) {
  transient.value = msg
  window.clearTimeout(transientTimer)
  transientTimer = window.setTimeout(() => { transient.value = '' }, 2800)
}
function notify(msg: string) { flash(msg); speak(msg) }

// ---------- 开合 / 问候 / 反馈 ----------
function openPop() {
  const fresh = !open.value
  open.value = true
  hintOn.value = false
  if (fresh && state.value === 'idle' && !callMode.value) greet()
}
function closePop() { open.value = false; greetActive.value = false; showVoice.value = false }
function togglePop() { open.value ? closePop() : openPop() }

let greetTimer = 0
function greet() {
  greetActive.value = true
  window.clearTimeout(greetTimer)
  greetTimer = window.setTimeout(() => { greetActive.value = false }, 4200)
}

const sparks = ref<{ k: number; x: number; y: number; c: string }[]>([])
let sparkSeq = 0
function spawnSparks() {
  const arr: typeof sparks.value = []
  for (let i = 0; i < 8; i++) {
    const ang = (Math.PI * 2 * i) / 8 + Math.random() * 0.5
    arr.push({ k: sparkSeq++, x: Math.cos(ang) * 58, y: Math.sin(ang) * 58, c: i % 3 === 0 ? '#a884ff' : i % 2 === 0 ? '#a9b8ff' : '#35e0d4' })
  }
  sparks.value = arr
  window.setTimeout(() => { sparks.value = [] }, 760)
}
function react() {
  reacting.value = true
  spawnSparks()
  window.setTimeout(() => { reacting.value = false }, 480)
}
function onCharClick() { react(); togglePop() }

// ---------- 待机随机小动作 ----------
const IDLE_ACTIONS: { cls: string; dur: number }[] = [
  { cls: 'do-look', dur: 1100 }, { cls: 'do-hop', dur: 800 }, { cls: 'do-sway', dur: 1600 }, { cls: 'do-stretch', dur: 1400 },
]
let actionTimer = 0
function scheduleAction() {
  window.clearTimeout(actionTimer)
  actionTimer = window.setTimeout(runIdleAction, 4200 + Math.random() * 5200)
}
function runIdleAction() {
  if (state.value === 'idle') {
    const a = IDLE_ACTIONS[Math.floor(Math.random() * IDLE_ACTIONS.length)]
    idleAction.value = a.cls
    window.setTimeout(() => { idleAction.value = '' }, a.dur)
  }
  scheduleAction()
}

// ---------- 语音合成 ----------
const voices = ref<SpeechSynthesisVoice[]>([])
const voiceURI = ref<string>(localStorage.getItem('dh_voice') || '')
const rate = ref<number>(Number(localStorage.getItem('dh_rate')) || 1)
const showVoice = ref(false)

function voiceScore(v: SpeechSynthesisVoice): number {
  const n = v.name.toLowerCase()
  let s = 0
  if (/natural|online|neural/.test(n)) s += 100
  if (/xiaoxiao|xiaoyi|yunxi|yunjian|yunyang|晓晓|云希|晓伊|晓萱/.test(n)) s += 80
  if (/google/.test(n)) s += 60
  if (/premium|enhanced|plus/.test(n)) s += 40
  if (v.lang === 'zh-CN') s += 20
  if (/huihui|kangkang|yaoyao/.test(n)) s -= 10
  return s
}
function loadVoices() {
  const all = window.speechSynthesis?.getVoices?.() || []
  if (!all.length) return
  voices.value = all
  if (!voiceURI.value || !all.some((v) => v.voiceURI === voiceURI.value)) {
    const zh = all.filter((v) => /^zh/i.test(v.lang))
    const pool = zh.length ? zh : all
    const best = pool.slice().sort((a, b) => voiceScore(b) - voiceScore(a))[0]
    if (best) voiceURI.value = best.voiceURI
  }
}
const currentVoice = computed(() => voices.value.find((v) => v.voiceURI === voiceURI.value) || null)
function saveVoice() {
  localStorage.setItem('dh_voice', voiceURI.value)
  localStorage.setItem('dh_rate', String(rate.value))
}
let pending = 0
function speak(text: string) {
  if (!ttsSupported || !text) return
  const u = new SpeechSynthesisUtterance(text)
  const v = currentVoice.value
  if (v) u.voice = v
  u.lang = v?.lang || 'zh-CN'
  u.rate = rate.value
  u.pitch = 1
  pending++
  u.onstart = () => { ttsSpeaking.value = true }
  const done = () => { pending = Math.max(0, pending - 1); if (pending === 0) ttsSpeaking.value = false }
  u.onend = done
  u.onerror = done
  window.speechSynthesis.speak(u)
}
function stopSpeaking() {
  try { window.speechSynthesis.cancel() } catch { /* ignore */ }
  pending = 0
  ttsSpeaking.value = false
}
function previewVoice() { stopSpeaking(); speak(t('human.greetSay')) }
function readLast() {
  const m = lastAssistant.value
  if (m?.content) speak(clip(m.content, 600))
  else notify(t('human.nothingToRead'))
}
function toggleAutoRead() {
  autoRead.value = !autoRead.value
  localStorage.setItem('dh_auto_read', autoRead.value ? '1' : '0')
  if (autoRead.value) {
    speakMsgId = null
    speakLen = 0
    flash(t('human.autoOn'))
  } else {
    stopSpeaking()
    flash(t('human.autoOff'))
  }
}

let speakMsgId: unknown = null
let speakLen = 0
function sentenceBoundary(s: string): number {
  let idx = 0
  const re = /[。！？!?；;.\n]/g
  let m: RegExpExecArray | null
  while ((m = re.exec(s))) idx = m.index + 1
  return idx
}
watch(lastAssistant, (m) => {
  if (!autoRead.value || !m || !ttsSupported) return
  if (m.id !== speakMsgId) { speakMsgId = m.id; speakLen = 0 }
  const text = m.content || ''
  if (text.length <= speakLen) return
  const seg = text.slice(speakLen)
  if (m.status === 'completed') {
    const chunk = seg.trim()
    if (chunk) speak(chunk)
    speakLen = text.length
  } else {
    const i = sentenceBoundary(seg)
    if (i > 0) { const chunk = seg.slice(0, i).trim(); if (chunk) speak(chunk); speakLen += i }
  }
}, { deep: true })

// ---------- 语音识别 ----------
let rec: any = null
let micReady = false
let starting = false
function ensureRec() {
  if (!SR) return null
  if (!rec) {
    rec = new SR()
    rec.lang = 'zh-CN'
    rec.interimResults = false
    rec.continuous = false
    rec.onresult = (e: any) => {
      const text = Array.from(e.results).map((r: any) => r[0].transcript).join('').trim()
      if (text) submit(text, true)
    }
    rec.onend = () => {
      listening.value = false
      if (callMode.value) maybeResumeCall(500)
    }
    rec.onerror = (e: any) => {
      listening.value = false
      const code = e?.error || 'unknown'
      if (callMode.value && (code === 'no-speech' || code === 'aborted')) { maybeResumeCall(400); return }
      if (code === 'not-allowed' || code === 'service-not-allowed') { voiceError.value = t('human.errDenied'); callMode.value = false }
      else if (code === 'audio-capture') { voiceError.value = t('human.errNoMic'); callMode.value = false }
      else if (code === 'no-speech') voiceError.value = t('human.errNoSpeech')
      else if (code === 'network') { voiceError.value = t('human.errService'); callMode.value = false }
      else voiceError.value = t('human.errGeneric', { e: code })
    }
  }
  return rec
}
async function ensureMic(): Promise<boolean> {
  if (micReady) return true
  if (!navigator.mediaDevices?.getUserMedia) { micReady = true; return true }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    stream.getTracks().forEach((tk) => tk.stop())
    micReady = true
    micAvailable.value = true
    return true
  } catch (e: any) {
    const name = e?.name || ''
    if (name === 'NotAllowedError' || name === 'SecurityError') voiceError.value = t('human.errDenied')
    else if (name === 'NotFoundError' || name === 'DevicesNotFoundError') {
      // 无设备:不由状态行报错,改由按钮自身置灰 + 悬浮提示
      micAvailable.value = false
      voiceError.value = ''
    }
    else voiceError.value = t('human.errGeneric', { e: name || String(e) })
    callMode.value = false
    return false
  }
}
/** 枚举音频输入设备;无设备时禁用麦克风相关入口(挂载时 + 设备插拔时刷新)。 */
async function detectMic() {
  const md = navigator.mediaDevices
  if (!md?.enumerateDevices) return
  try {
    const devices = await md.enumerateDevices()
    const hasMic = devices.some((d) => d.kind === 'audioinput')
    micAvailable.value = hasMic
    if (!hasMic) {
      if (listening.value) stopListening()
      if (callMode.value) exitCall()
    }
  } catch {
    // 无法枚举时按“可用”处理,点击后再走权限/设备错误提示
  }
}
function stopListening() {
  try { rec?.stop() } catch { /* ignore */ }
  listening.value = false
}
async function startListening() {
  if (starting || listening.value) return
  voiceError.value = ''
  if (!secureContext) { voiceError.value = t('human.errInsecure'); callMode.value = false; openPop(); return }
  const r = ensureRec()
  if (!r) { voiceError.value = t('human.unsupported'); callMode.value = false; openPop(); return }
  starting = true
  try {
    if (!(await ensureMic())) return
    r.start()
    listening.value = true
  } catch (e: any) {
    listening.value = false
    if (!callMode.value) voiceError.value = t('human.errGeneric', { e: e?.name || String(e) })
  } finally {
    starting = false
  }
}
function onMicClick() {
  if (ttsSpeaking.value) { stopSpeaking(); startListening(); return }
  if (listening.value) stopListening()
  else startListening()
}

// ---------- 通话模式 ----------
let callRestartTimer = 0
function maybeResumeCall(delay = 700) {
  window.clearTimeout(callRestartTimer)
  if (!callMode.value) return
  callRestartTimer = window.setTimeout(() => {
    if (callMode.value && !listening.value && !chat.isStreaming && !ttsSpeaking.value) startListening()
  }, delay)
}
function enterCall() {
  if (!micSupported) { voiceError.value = t('human.unsupported'); openPop(); return }
  callMode.value = true
  openPop()
  speak(t('human.callSay'))
  if (!ttsSupported) startListening()
}
function exitCall() {
  callMode.value = false
  window.clearTimeout(callRestartTimer)
  if (listening.value) stopListening()
  stopSpeaking()
}
function toggleCall() { callMode.value ? exitCall() : enterCall() }
watch([() => chat.isStreaming, ttsSpeaking], () => {
  if (callMode.value && !chat.isStreaming && !ttsSpeaking.value) maybeResumeCall(700)
})

// ---------- 发送 ----------
async function send(text: string) {
  const val = text.trim()
  if (!val) return
  stopSpeaking()
  openPop()
  if (chat.isStreaming) chat.abort()
  await chat.sendMessage(val)
}
function submit(text: string, fromVoice: boolean) {
  const val = text.trim()
  if (!val) { react(); return }
  if (fromVoice && val.length <= 16 && handleCommand(val)) return
  send(val)
}

// ---------- 语音指令 ----------
function openBrowser() { chat.browserToolSignal++; notify(t('human.cmdBrowser')) }
function openFiles() {
  if (sidebarEvents?.value) {
    sidebarEvents.value.openMyFiles = true
    window.setTimeout(() => { if (sidebarEvents?.value) sidebarEvents.value.openMyFiles = false }, 200)
  }
  notify(t('human.cmdFiles'))
}
async function exportSession() {
  if (!chat.currentSessionId) { notify(t('human.noSession')); return }
  try { await chat.exportSession(chat.currentSessionId, 'md'); notify(t('human.cmdExported')) }
  catch { notify(t('human.failed')) }
}
function switchSession(dir: number) {
  const list = chat.sessions
  const idx = list.findIndex((s) => s.id === chat.currentSessionId)
  const next = list[idx + dir]
  if (next) { chat.selectSession(next.id); notify(t('human.cmdSession')) }
  else notify(t('human.noSession'))
}
function bumpRate(d: number) {
  rate.value = Math.min(1.6, Math.max(0.6, Math.round((rate.value + d) * 100) / 100))
  saveVoice()
  notify(t('human.cmdRate'))
}
const commands = computed<{ re: RegExp; act: () => void }[]>(() => [
  { re: /(停止朗读|别说了|安静|闭嘴|不要说了)/, act: stopSpeaking },
  { re: /(停止生成|中断回答|打断|停止回答)/, act: () => chat.abort() },
  { re: /(重说一遍|再说一次|重新说|再读一遍|重复一遍)/, act: readLast },
  { re: /(慢一点|慢点|说慢点|讲慢点)/, act: () => bumpRate(-0.1) },
  { re: /(快一点|快点|说快点|讲快点)/, act: () => bumpRate(0.1) },
  { re: /(打开浏览器|看浏览器|浏览器面板)/, act: openBrowser },
  { re: /(打开文件|文件面板|工作区)/, act: openFiles },
  { re: /(导出|导出会话|导出报告)/, act: exportSession },
  { re: /(新建对话|新对话|开个新对话)/, act: () => { chat.startDraft(); notify(t('human.cmdNewChat')) } },
  { re: /(上一个会话|上一条会话|上一个对话)/, act: () => switchSession(-1) },
  { re: /(下一个会话|下一条会话|下一个对话)/, act: () => switchSession(1) },
  { re: /(生成周报|写周报|数据分析周报)/, act: () => taskWeekly() },
  { re: /(设为定时|定时任务|每天提醒)/, act: () => scheduleTask() },
  { re: /(结束通话|退出通话|挂断|结束对话)/, act: exitCall },
])
function handleCommand(text: string): boolean {
  const c = commands.value.find((x) => x.re.test(text))
  if (!c) return false
  c.act()
  return true
}

// ---------- 一键任务 ----------
async function findDataFile(): Promise<string> {
  for (const dir of ['data', 'uploads', '']) {
    try {
      const nodes = await fileApi.list(dir)
      const f = (nodes || []).find((n) => !n.directory && /\.(csv|xlsx|xls|json)$/i.test(n.name || ''))
      if (f?.path) return f.path
    } catch { /* ignore */ }
  }
  return ''
}
async function taskWeekly() {
  openPop()
  const path = await findDataFile()
  const line = path ? `\n\n已上传以下文件到工作区,可使用相对路径读取:\n[附件] ${path}` : ''
  send(t('human.taskWeeklyPrompt', { src: path || 'data/ 目录' }) + line)
}
async function scheduleTask() {
  openPop()
  const lastUser = [...chat.messages].reverse().find((m) => m.role === 'user')
  const prompt = (lastUser?.content || '').replace(/\[附件\][^\n]*/g, '').trim() || t('human.taskWeeklyPrompt', { src: 'data/' })
  try {
    await jobApi.create({ name: t('human.jobName'), prompt, cron: '0 9 * * *' })
    notify(t('human.scheduled'))
    react()
  } catch { notify(t('human.failed')) }
}

watch([() => chat.isStreaming, ttsSpeaking], () => {
  if (callMode.value && !chat.isStreaming && !ttsSpeaking.value) maybeResumeCall(700)
})

// ---------- 扇状动作 ----------
const fanItems = computed(() => {
  const reading = ttsSpeaking.value
  const base = [
    { key: 'mic', icon: 'mic', label: 'human.listen', act: onMicClick, disabled: !micAvailable.value },
    { key: 'call', icon: 'phone', label: 'human.call', act: toggleCall, disabled: !micAvailable.value },
    {
      key: 'read',
      icon: reading ? 'stop' : 'volume',
      label: reading ? 'human.stopSpeak' : 'human.capRead',
      act: () => (reading ? stopSpeaking() : readLast()),
      disabled: false,
    },
    { key: 'auto', icon: 'repeat', label: 'human.autoSpeak', act: toggleAutoRead, disabled: false },
    { key: 'browser', icon: 'globe', label: 'human.capBrowser', act: openBrowser, disabled: false },
    { key: 'export', icon: 'download', label: 'human.capExport', act: exportSession, disabled: false },
    { key: 'voice', icon: 'sliders', label: 'human.voice', act: () => { showVoice.value = !showVoice.value }, disabled: false },
  ]
  const n = base.length
  const start = 90
  const spread = n > 6 ? 128 : 112
  const R = n > 6 ? 114 : 108
  return base.map((it, i) => {
    const deg = start + (spread / (n - 1)) * i
    const a = deg * Math.PI / 180
    return { ...it, x: Math.cos(a) * R, y: -Math.sin(a) * R }
  })
})
function fanTransform(it: { x: number; y: number }): string {
  return open.value
    ? `translate(-50%, -50%) translate(${it.x}px, ${it.y}px) scale(1)`
    : 'translate(-50%, -50%) scale(.3)'
}

// ---------- 生命周期 ----------
function onDocClick(e: MouseEvent) {
  if (!open.value) return
  if ((e.target as HTMLElement).closest('.dh-assistant')) return
  closePop()
}
function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') { if (callMode.value) exitCall(); else if (open.value) closePop() }
}
onMounted(() => {
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onKey)
  hintOn.value = true
  window.setTimeout(() => { if (!open.value) hintOn.value = false }, 5600)
  scheduleAction()
  loadVoices()
  scheduleBlink()
  detectMic()
  navigator.mediaDevices?.addEventListener?.('devicechange', detectMic)
  if (ttsSupported) window.speechSynthesis.addEventListener('voiceschanged', loadVoices)
})
onBeforeUnmount(() => {
  stopSpeaking()
  try { rec?.stop() } catch { /* ignore */ }
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onKey)
  navigator.mediaDevices?.removeEventListener?.('devicechange', detectMic)
  if (ttsSupported) window.speechSynthesis.removeEventListener('voiceschanged', loadVoices)
  window.clearTimeout(greetTimer)
  window.clearTimeout(actionTimer)
  window.clearTimeout(blinkTimer)
  window.clearTimeout(callRestartTimer)
  window.clearTimeout(transientTimer)
})
</script>

<template>
  <div class="dh-root">
    <!-- 图标精灵:与全站一致的线性图标(24 栅格、描边 1.8,继承 currentColor) -->
    <svg class="dh-defs" aria-hidden="true" focusable="false">
      <defs>
        <symbol id="dh-ico-mic" viewBox="0 0 24 24">
          <path d="M12 2a2.5 2.5 0 0 0-2.5 2.5v7a2.5 2.5 0 0 0 5 0v-7A2.5 2.5 0 0 0 12 2z" />
          <path d="M19 10.5v1a7 7 0 0 1-14 0v-1" />
          <path d="M12 18.5V22M8.5 22h7" />
        </symbol>
        <symbol id="dh-ico-phone" viewBox="0 0 24 24">
          <path d="M21 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 1.1 4.2 2 2 0 0 1 3.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L7.1 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2z" />
        </symbol>
        <symbol id="dh-ico-volume" viewBox="0 0 24 24">
          <path d="M11 5 6 9H2v6h4l5 4V5z" />
          <path d="M15.5 8.5a5 5 0 0 1 0 7" />
        </symbol>
        <symbol id="dh-ico-stop" viewBox="0 0 24 24">
          <rect x="6.5" y="6.5" width="11" height="11" rx="2" />
        </symbol>
        <symbol id="dh-ico-repeat" viewBox="0 0 24 24">
          <path d="m17 2.5 4 4-4 4" />
          <path d="M3 11.5v-2a4 4 0 0 1 4-4h14" />
          <path d="m7 21.5-4-4 4-4" />
          <path d="M21 12.5v2a4 4 0 0 1-4 4H3" />
        </symbol>
        <symbol id="dh-ico-globe" viewBox="0 0 24 24">
          <circle cx="12" cy="12" r="10" />
          <path d="M2 12h20" />
          <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
        </symbol>
        <symbol id="dh-ico-download" viewBox="0 0 24 24">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
          <path d="m7 10 5 5 5-5" />
          <path d="M12 15V3" />
        </symbol>
        <symbol id="dh-ico-sliders" viewBox="0 0 24 24">
          <path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3" />
          <path d="M1 14h6M9 8h6M17 16h6" />
        </symbol>
      </defs>
    </svg>

    <div class="dh-assistant" :class="{ 'is-react': reacting }" :data-state="state">
      <!-- 头顶扇状动作 -->
      <div class="dh-fan" :class="{ on: open && !showVoice }">
        <button
          v-for="(it, i) in fanItems"
          :key="it.key"
          class="dh-fanbtn"
          :class="{ 'is-active': (it.key === 'call' && callMode) || (it.key === 'mic' && listening) || (it.key === 'auto' && autoRead), 'is-disabled': it.disabled }"
          :style="{ transform: fanTransform(it), transitionDelay: (open ? i * 36 : 0) + 'ms' }"
          :title="it.disabled ? undefined : t(it.label)"
          :aria-pressed="it.key === 'auto' ? autoRead : undefined"
          :aria-disabled="it.disabled || undefined"
          :aria-label="it.disabled ? t('human.noMicTip') : t(it.label)"
          @click="!it.disabled && it.act()"
        >
          <svg class="dh-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <use :href="'#dh-ico-' + it.icon" />
          </svg>
          <!-- 无麦克风提示:锚定在按钮本身,随按钮位置显示 -->
          <span v-if="it.disabled" class="dh-tip">{{ t('human.noMicTip') }}</span>
        </button>
      </div>

      <!-- 台词/状态(无框) -->
      <div class="dh-say" :class="{ on: open && !!statusText && !showVoice, err: !!voiceError }">{{ statusText }}</div>

      <!-- 音色设置 -->
      <div v-if="open && showVoice" class="dh-voice">
        <label class="dh-vrow">
          <span>{{ t('human.voice') }}</span>
          <select v-model="voiceURI" @change="saveVoice">
            <option v-for="v in voices" :key="v.voiceURI" :value="v.voiceURI">{{ v.name }} · {{ v.lang }}</option>
          </select>
        </label>
        <label class="dh-vrow">
          <span>{{ t('human.rate') }}</span>
          <input type="range" min="0.6" max="1.6" step="0.05" v-model.number="rate" @change="saveVoice" />
        </label>
        <button class="dh-chip" @click="previewVoice">{{ t('human.preview') }}</button>
      </div>

      <!-- 角色本体 -->
      <div class="dh-charwrap">
        <span class="dh-aura" />
        <span class="dh-ring" />
        <span class="dh-ring2" />
        <span class="dh-glow" />
        <span class="dh-hint" :class="{ on: hintOn && !open }">{{ t('human.hint') }}</span>
        <button class="dh-char" :class="idleAction" :aria-label="t('human.title')" @click="onCharClick">
          <span class="dh-body">
            <img class="dh-img" :src="baseImg" alt="" draggable="false" />
            <img class="dh-part dh-p-l dh-eye" :class="{ blink }" :src="eyeLImg" alt="" draggable="false" />
            <img class="dh-part dh-p-r dh-eye" :class="{ blink }" :src="eyeRImg" alt="" draggable="false" />
            <img class="dh-part dh-p-m" :src="mouthImg" alt="" draggable="false" />
          </span>
        </button>
        <span class="dh-wavebar" :class="{ on: state === 'listen' || state === 'speak' }">
          <i /><i /><i /><i /><i /><i /><i />
        </span>
        <span
          v-for="s in sparks"
          :key="s.k"
          class="dh-spark"
          :style="{ '--dx': s.x + 'px', '--dy': s.y + 'px', background: s.c }"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.dh-root {
  position: fixed; right: 26px; bottom: 24px; z-index: 60;
  --dh-teal: #35e0d4;
  --dh-violet: #a884ff;
  --dh-blue: var(--accent);
  --dh-panel: var(--bg-elev);
  --dh-rule: var(--border);
  --dh-rule2: color-mix(in srgb, var(--text) 18%, transparent);
}
.dh-assistant { position: relative; display: flex; flex-direction: column; align-items: center; gap: 10px; pointer-events: none; }
.dh-defs { position: absolute; width: 0; height: 0; overflow: hidden; }

/* 头顶扇状动作 */
.dh-fan { position: absolute; left: 50%; top: 6px; width: 0; height: 0; z-index: 6; }
.dh-fanbtn {
  position: absolute; left: 0; top: 0; width: 40px; height: 40px; border-radius: 10px; cursor: pointer;
  padding: 0;
  display: flex; align-items: center; justify-content: center;
  border: 1px solid var(--border); background: var(--bg-elev); color: var(--text-2);
  box-shadow: var(--shadow); backdrop-filter: blur(10px);
  opacity: 0; pointer-events: none;
  transition: transform .32s cubic-bezier(.2, .9, .3, 1.35), opacity .22s ease, background .15s, color .15s, border-color .15s;
}
.dh-ico { width: 18px; height: 18px; display: block; }
.dh-fan.on .dh-fanbtn { opacity: 1; pointer-events: auto; }
.dh-fanbtn:hover { background: var(--surface-hover); color: var(--text); border-color: var(--dh-rule2); }
.dh-fanbtn:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.dh-fanbtn.is-active { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
/* 无麦克风设备:置灰但保留 hover/悬浮提示 */
.dh-fan.on .dh-fanbtn.is-disabled { opacity: .38; cursor: not-allowed; }
.dh-fanbtn.is-disabled:hover { background: var(--bg-elev); color: var(--text-2); border-color: var(--border); z-index: 10; }
/* 悬浮提示:相对按钮定位(按钮自身是 absolute + transform,提示随其移动) */
.dh-tip {
  position: absolute; left: 50%; bottom: calc(100% + 7px);
  transform: translateX(-50%) translateY(3px);
  max-width: 180px; padding: 5px 9px; border-radius: 8px;
  font-size: 11.5px; line-height: 1.4; white-space: nowrap; color: var(--text);
  background: var(--bg-elev); border: 1px solid var(--dh-rule2);
  box-shadow: 0 10px 24px rgba(0, 0, 0, .45);
  opacity: 0; pointer-events: none; z-index: 9;
  transition: opacity .16s ease, transform .16s ease;
}
.dh-fanbtn.is-disabled:hover .dh-tip,
.dh-fanbtn.is-disabled:focus-visible .dh-tip { opacity: 1; transform: translateX(-50%) translateY(0); }

/* 台词/状态(无框,纯文本,置于角色左侧) */
.dh-say {
  position: absolute; right: calc(100% + 14px); top: 50%; transform: translateY(-50%) translateX(6px);
  width: 224px; text-align: left; font-size: 12.5px; line-height: 1.55; color: var(--text);
  text-shadow: 0 1px 3px rgba(0, 0, 0, .7), 0 0 12px rgba(0, 0, 0, .55);
  opacity: 0; transition: opacity .25s ease, transform .25s ease; pointer-events: none; z-index: 7;
}
.dh-say.on { opacity: 1; transform: translateY(-50%) translateX(0); }
.dh-say.err { color: #ff8a8a; }

/* 音色设置(置于角色左侧) */
.dh-voice {
  position: absolute; right: calc(100% + 14px); top: 50%; transform: translateY(-50%);
  width: 216px; display: flex; flex-direction: column; gap: 7px; padding: 11px 12px;
  background: var(--dh-panel); border: 1px solid var(--dh-rule2); border-radius: 12px; z-index: 8;
  box-shadow: 0 16px 38px rgba(0, 0, 0, .5); pointer-events: auto;
}
.dh-vrow { display: flex; align-items: center; gap: 8px; font-size: 11.5px; color: var(--text-3); }
.dh-vrow > span { flex: 0 0 auto; width: 34px; }
.dh-vrow select { flex: 1; min-width: 0; height: 26px; border-radius: 8px; border: 1px solid var(--dh-rule); background: var(--dh-panel); color: var(--text); font-size: 11.5px; }
.dh-vrow input[type='range'] { flex: 1; min-width: 0; accent-color: var(--dh-blue); }
.dh-chip {
  font-size: 11.5px; color: var(--text-2); cursor: pointer; align-self: flex-start;
  border: 1px solid var(--dh-rule); background: var(--fill-0); border-radius: 999px; padding: 4px 10px;
}
.dh-chip:hover { color: var(--text); border-color: rgba(91, 120, 255, .5); }

.dh-charwrap { position: relative; display: flex; align-items: flex-end; justify-content: center; }
.dh-char {
  position: relative; z-index: 2; cursor: pointer; padding: 0; border: 0; background: none;
  width: 78px; display: block; transition: transform .25s ease; pointer-events: auto;
}
.dh-char:hover { transform: translateY(-4px); }
.dh-body {
  position: relative; display: block; width: 100%;
  filter: drop-shadow(0 18px 28px rgba(0, 0, 0, .55)) drop-shadow(0 6px 22px rgba(91, 120, 255, .32));
  transform-origin: 50% 100%; animation: dhFloat 3.8s ease-in-out infinite; will-change: transform;
}
.dh-img { display: block; width: 100%; height: auto; }
.dh-part { position: absolute; pointer-events: none; }
.dh-p-l { left: 26%; top: 22.16%; width: 18.67%; height: 6.44%; }
.dh-p-r { left: 55.33%; top: 22.16%; width: 18.67%; height: 6.44%; }
.dh-p-m { left: 42%; top: 29.38%; width: 18.67%; height: 3.87%; }
.dh-eye { transform-origin: 50% 50%; }
.dh-eye.blink { animation: dhBlink .16s ease-in-out; }
@keyframes dhBlink { 0%, 100% { transform: scaleY(1); } 50% { transform: scaleY(.06); } }

.dh-aura {
  position: absolute; left: 50%; bottom: -22px; width: 260px; height: 260px; transform: translateX(-50%);
  border-radius: 50%; z-index: 0; pointer-events: none;
  background: radial-gradient(closest-side, rgba(53, 224, 212, .2), transparent 70%); transition: background .5s ease;
}
.dh-glow {
  position: absolute; left: 50%; bottom: 0; width: 120px; height: 26px; transform: translateX(-50%);
  border-radius: 50%; z-index: 1; pointer-events: none;
  background: radial-gradient(closest-side, rgba(53, 224, 212, .5), transparent 76%); animation: dhGlow 3.8s ease-in-out infinite;
}
.dh-ring {
  position: absolute; left: 50%; bottom: -72px; width: 150px; height: 150px; transform: translateX(-50%) scaleY(.28);
  border-radius: 50%; border: 1px dashed rgba(53, 224, 212, .42); z-index: 1; pointer-events: none; animation: dhRingSpin 16s linear infinite;
}
.dh-ring2 {
  position: absolute; left: 50%; bottom: -46px; width: 96px; height: 96px; transform: translateX(-50%) scaleY(.3);
  border-radius: 50%; border: 1px solid rgba(109, 124, 255, .5); z-index: 1; pointer-events: none; animation: dhRingPulse 3.8s ease-out infinite;
}
.dh-wavebar {
  position: absolute; left: 50%; bottom: 8px; transform: translateX(-50%);
  height: 20px; display: flex; align-items: flex-end; justify-content: center; gap: 3px;
  opacity: 0; transition: opacity .25s ease; z-index: 3; pointer-events: none;
}
.dh-wavebar.on { opacity: 1; }
.dh-wavebar i { width: 3px; height: 6px; border-radius: 2px; background: var(--dh-teal); opacity: .9; }
.dh-wavebar.on i { animation: dhBar .7s ease-in-out infinite; }
.dh-wavebar i:nth-child(2) { animation-delay: .1s; }
.dh-wavebar i:nth-child(3) { animation-delay: .2s; }
.dh-wavebar i:nth-child(4) { animation-delay: .3s; }
.dh-wavebar i:nth-child(5) { animation-delay: .4s; }
.dh-wavebar i:nth-child(6) { animation-delay: .5s; }
.dh-wavebar i:nth-child(7) { animation-delay: .15s; }

.dh-hint {
  position: absolute; left: 50%; bottom: 100%; transform: translateX(-50%) translateY(8px);
  background: var(--dh-panel); border: 1px solid var(--dh-rule2); color: var(--text);
  font-size: 12px; line-height: 1.5; padding: 7px 12px; border-radius: 12px; white-space: nowrap;
  box-shadow: 0 14px 32px rgba(0, 0, 0, .5); opacity: 0; transition: .3s ease; pointer-events: none; z-index: 6;
}
.dh-hint.on { opacity: 1; transform: translateX(-50%) translateY(0); }

@keyframes dhFloat { 0%, 100% { transform: translateY(0) scale(1); } 50% { transform: translateY(-13px) scale(1.012); } }
@keyframes dhGlow { 0%, 100% { transform: translateX(-50%) scale(1); opacity: .85; } 50% { transform: translateX(-50%) scale(1.14); opacity: 1; } }
@keyframes dhRingSpin { from { transform: translateX(-50%) scaleY(.28) rotate(0deg); } to { transform: translateX(-50%) scaleY(.28) rotate(360deg); } }
@keyframes dhRingPulse { 0% { transform: translateX(-50%) scaleY(.3) scale(.86); opacity: .65; } 100% { transform: translateX(-50%) scaleY(.3) scale(1.34); opacity: 0; } }
@keyframes dhBar { 0%, 100% { height: 5px; } 50% { height: 18px; } }
@keyframes dhSparkFly { from { opacity: 1; transform: translate(0, 0) scale(1); } to { opacity: 0; transform: translate(var(--dx), var(--dy)) scale(.2); } }

[data-state="greet"] .dh-body { animation: dhGreet 1s ease-in-out 2; }
@keyframes dhGreet { 0%, 100% { transform: translateY(0) rotate(0); } 30% { transform: translateY(-24px) rotate(-3.5deg); } 62% { transform: translateY(-5px) rotate(1.6deg); } }
[data-state="greet"] .dh-aura { background: radial-gradient(closest-side, rgba(53, 224, 212, .42), transparent 70%); }

[data-state="listen"] .dh-body { animation: dhListen 1s ease-in-out infinite; }
@keyframes dhListen { 0%, 100% { transform: translateY(0) scale(1); } 50% { transform: translateY(-6px) scale(1.02); } }
[data-state="listen"] .dh-aura { background: radial-gradient(closest-side, rgba(61, 220, 151, .34), transparent 70%); }

[data-state="think"] .dh-body { animation: dhThink 2.4s ease-in-out infinite; }
@keyframes dhThink { 0%, 100% { transform: rotate(-2.4deg) translateY(-3px); } 50% { transform: rotate(2.4deg) translateY(-3px); } }
[data-state="think"] .dh-aura { background: radial-gradient(closest-side, rgba(168, 132, 255, .32), transparent 70%); }
[data-state="think"] .dh-ring { animation-duration: 3.6s; animation-direction: reverse; }

[data-state="speak"] .dh-body { animation: dhSpeak 1s ease-in-out infinite; }
@keyframes dhSpeak { 0%, 100% { transform: translateY(0) scale(1); } 50% { transform: translateY(-9px) scale(1.016); } }
[data-state="speak"] .dh-aura { background: radial-gradient(closest-side, rgba(91, 120, 255, .36), transparent 70%); }
[data-state="speak"] .dh-wavebar.on i { animation-duration: .5s; }

.is-react .dh-body { animation: dhReact .46s cubic-bezier(.2, .9, .3, 1.4) !important; }
@keyframes dhReact { 0% { transform: scale(1, 1); } 35% { transform: scale(1.08, .92); } 65% { transform: scale(.96, 1.04); } 100% { transform: scale(1, 1); } }

.dh-char.do-look .dh-body { animation: dhLook 1.1s ease-in-out; }
@keyframes dhLook { 0%, 100% { transform: rotate(0); } 28% { transform: rotate(-4.5deg) translateY(-4px); } 60% { transform: rotate(3deg) translateY(-2px); } }
.dh-char.do-hop .dh-body { animation: dhHop .8s ease-out; }
@keyframes dhHop { 0% { transform: translateY(0); } 38% { transform: translateY(-20px) scale(.99, 1.02); } 100% { transform: translateY(0); } }
.dh-char.do-sway .dh-body { animation: dhSway 1.6s ease-in-out; }
@keyframes dhSway { 0%, 100% { transform: translateX(0) rotate(0); } 25% { transform: translateX(-6px) rotate(-2.2deg); } 75% { transform: translateX(6px) rotate(2.2deg); } }
.dh-char.do-stretch .dh-body { animation: dhStretch 1.4s ease-in-out; }
@keyframes dhStretch { 0%, 100% { transform: scale(1, 1); } 45% { transform: scale(.97, 1.05); } }

.dh-spark { position: absolute; left: 50%; top: 42%; width: 7px; height: 7px; border-radius: 50%; pointer-events: none; z-index: 5; animation: dhSparkFly .7s ease-out forwards; }

@media (max-width: 760px) {
  .dh-root { right: 12px; bottom: 12px; }
  .dh-char { width: 64px; }
}
@media (prefers-reduced-motion: reduce) {
  .dh-body, .dh-glow, .dh-ring, .dh-ring2, .dh-wavebar i, .dh-spark, .dh-fanbtn { animation: none !important; }
}
</style>
