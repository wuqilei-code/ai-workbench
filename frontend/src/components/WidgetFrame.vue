<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { fileApi } from '@/api/files'
import { useThemeStore } from '@/stores/theme'

/**
 * dynamic-ui 内联可视化组件渲染器:
 * - 文件模式:从工作区取 widget_code(widgets/*.html),在 sandbox iframe(allow-scripts,无同源)内渲染;
 * - 流式模式(liveCode):iframe 只加载一次壳文档,此后通过 postMessage 增量下发代码,
 *   壳内用「morph 合并」更新 DOM —— 已有节点只改属性/文本(不重建),SVG/CSS 过渡因此平滑连续;
 * - 注入主题契约(:root[data-widget-theme])与 sendPrompt 桥(window.sendPrompt → 父页聊天输入);
 * - ResizeObserver 同步组件高度;CDN 白名单由技能提示词约束,iframe 隔离兜底。
 */
const props = defineProps<{ path?: string; liveCode?: string | null }>()
const emit = defineEmits<{ (e: 'prompt', text: string): void }>()

const theme = useThemeStore()
const srcdoc = ref('')
const failed = ref(false)
const height = ref(220)
const iframeRef = ref<HTMLIFrameElement | null>(null)
const dlBusy = ref(false)

function isDark(): boolean {
  return theme.mode === 'dark'
}

/** 流式期间移除 script 块(含尾部未闭合的),JS 图表等定稿后由文件版组件统一执行 */
function stripScripts(code: string): string {
  return code.replace(/<script\b[\s\S]*?<\/script\s*>/gi, '').replace(/<script\b[\s\S]*$/i, '')
}

/**
 * 把生成的 CSS 里的 `@media (prefers-color-scheme: dark|light) { :root { ... } }`
 * 转成 `:root[data-widget-theme="dark"] { ... }` 属性门控,让组件跟随 App 主题
 * (壳 html 上的 data-widget-theme),而不是系统/浏览器的明暗偏好。
 */
function normalizeWidgetTheme(code: string): string {
  const RE = /@media\s*\(\s*prefers-color-scheme\s*:\s*(dark|light)\s*\)\s*\{/gi
  let out = ''
  let last = 0
  let m: RegExpExecArray | null
  while ((m = RE.exec(code))) {
    const bodyStart = m.index + m[0].length
    let depth = 1
    let i = bodyStart
    for (; i < code.length && depth > 0; i++) {
      if (code[i] === '{') depth++
      else if (code[i] === '}') depth--
    }
    const closeIdx = i - 1
    const scheme = m[1] === 'dark' ? 'dark' : 'light'
    const inner = code.slice(bodyStart, closeIdx)
    out += code.slice(last, m.index)
    out += inner.replace(/:root\b/g, `:root[data-widget-theme="${scheme}"]`)
    last = closeIdx + 1
  }
  out += code.slice(last)
  return out
}

const BOOT = `
<script>
(function(){
  var root=document.getElementById('__wd');
  var send=function(m){try{parent.postMessage(Object.assign({__aiwb_widget:1},m),'*')}catch(e){}};
  window.sendPrompt=function(p){send({type:'prompt',prompt:String(p||'')})};
  window.addEventListener('message',function(e){
    var d=e.data;if(!d||d.__aiwb_host!==1)return;
    if(d.type==='theme'&&document.documentElement){document.documentElement.setAttribute('data-widget-theme',d.value);return}
    if(d.type==='patch'){apply(String(d.html||''))}
  });
  function setAttrs(dn,sn){
    var sa=sn.attributes,i,names=[];
    for(i=0;i<sa.length;i++)names.push(sa[i].name);
    for(i=0;i<names.length;i++){
      var v=sn.getAttribute(names[i]);
      if(dn.getAttribute(names[i])!==v)dn.setAttribute(names[i],v);
    }
    var da=dn.attributes,k,drop=[];
    for(i=0;i<da.length;i++){k=da[i].name;if(sn.getAttribute(k)===null)drop.push(k)}
    for(i=0;i<drop.length;i++)dn.removeAttribute(drop[i]);
  }
  function mergeNode(dn,sn){
    if(dn.nodeType===3||dn.nodeType===8){if(dn.nodeValue!==sn.nodeValue)dn.nodeValue=sn.nodeValue;return}
    setAttrs(dn,sn);
    reconcileChildren(dn,sn);
  }
  function reconcileChildren(dp,sp){
    var sc=sp.childNodes,dn,i;
    for(i=0;i<sc.length;i++){
      dn=dp.childNodes[i];
      if(!dn){dp.appendChild(sc[i].cloneNode(true))}
      else if(dn.nodeType!==sc[i].nodeType||dn.nodeName!==sc[i].nodeName){dp.replaceChild(sc[i].cloneNode(true),dn)}
      else{mergeNode(dn,sc[i])}
    }
    while(dp.childNodes.length>sc.length){dp.removeChild(dp.lastChild)}
  }
  function apply(html){
    try{
      var tpl=document.createElement('template');tpl.innerHTML=html;
      reconcileChildren(root,tpl.content);
      while(root.firstChild&&root.firstChild.nodeType===3&&!root.firstChild.nodeValue.trim())root.removeChild(root.firstChild);
    }catch(err){root.innerHTML=html}
    requestAnimationFrame(reportH);
  }
  function reportH(){send({type:'height',height:Math.ceil(document.documentElement.getBoundingClientRect().height)})}
  if('ResizeObserver' in window){new ResizeObserver(reportH).observe(document.documentElement)}
  window.addEventListener('load',function(){reportH();send({type:'ready'})});
  setTimeout(function(){reportH();send({type:'ready'})},600);
  window.addEventListener('wheel',function(e){
    var de=document.documentElement;
    if(de.scrollHeight<=de.clientHeight+1){
      e.preventDefault();
      send({type:'wheel',dy:e.deltaY,dx:e.deltaX||0});
    }
  },{passive:false});
})();
<\/script>`

/** 文件模式:整页一次性渲染 */
function balancedScripts(code: string): string {
  // 流式生成可能中途被截断:<script> 未闭合会把后续注入的 BOOT 吞进字符串,
  // 导致 BOOT 永不执行(高度停 220、滚轮桥失效)。按数量补齐闭合标签。
  const open = (code.match(/<script\b/g) || []).length
  const close = (code.match(/<\/script>/g) || []).length
  return open > close ? code + '<' + '/script>'.repeat(open - close) : code
}

function buildDoc(code: string): string {
  return (
    `<!doctype html><html data-widget-theme="${isDark() ? 'dark' : 'light'}"><head><meta charset="utf-8">` +
    `<meta name="viewport" content="width=device-width, initial-scale=1">` +
    `<style>html,body{margin:0;padding:0;background:transparent}</style></head><body>` +
    balancedScripts(normalizeWidgetTheme(code)) +
    BOOT +
    `</body></html>`
  )
}

// ---------- 流式模式:壳只加载一次,增量 patch ----------

const LIVE_THROTTLE = 150
let _liveTimer: ReturnType<typeof setTimeout> | null = null
let _pendingLive = ''
let _liveReady = false

function buildLiveShell(): string {
  return (
    `<!doctype html><html data-widget-theme="${isDark() ? 'dark' : 'light'}"><head><meta charset="utf-8">` +
    `<meta name="viewport" content="width=device-width, initial-scale=1">` +
    `<style>html,body{margin:0;padding:0;background:transparent}</style>` +
    `</head><body><div id="__wd"></div>${BOOT}</body></html>`
  )
}

function postToFrame(payload: Record<string, unknown>): boolean {
  const w = iframeRef.value?.contentWindow
  if (!w) return false
  try {
    w.postMessage(Object.assign({ __aiwb_host: 1 }, payload), '*')
    return true
  } catch {
    return false
  }
}

function pushPatch(code: string) {
  const stripped = stripScripts(normalizeWidgetTheme(code))
  if (!stripped.trim()) return
  if (!_liveReady || !postToFrame({ type: 'patch', html: stripped })) {
    _pendingLive = code
  }
}

function scheduleLive(code: string) {
  _pendingLive = code
  if (_liveTimer) return
  _liveTimer = setTimeout(() => {
    _liveTimer = null
    pushPatch(_pendingLive)
  }, LIVE_THROTTLE)
}

function stopLiveTimer() {
  if (_liveTimer) {
    clearTimeout(_liveTimer)
    _liveTimer = null
  }
}

watch(() => props.liveCode, (v) => {
  if (v == null) return
  scheduleLive(v)
})

async function load() {
  failed.value = false
  srcdoc.value = ''
  try {
    const raw = await fileApi.content(props.path || '')
    if (!raw) throw new Error('empty')
    srcdoc.value = buildDoc(String(raw))
  } catch {
    failed.value = true
  }
}

async function download() {
  if (!props.path || dlBusy.value) return
  dlBusy.value = true
  try {
    await fileApi.download(props.path)
  } finally {
    dlBusy.value = false
  }
}

function onMessage(e: MessageEvent) {
  const d = e.data
  if (!d || d.__aiwb_widget !== 1) return
  if (e.source !== iframeRef.value?.contentWindow) return
  if (d.type === 'ready') {
    _liveReady = true
    if (_pendingLive) pushPatch(_pendingLive)
    console.debug('[widget] live ready')
  } else if (d.type === 'height' && typeof d.height === 'number') {
    height.value = Math.max(80, Math.min(3000, d.height))
    console.debug('[widget] height', d.height)
  } else if (d.type === 'wheel' && typeof d.dy === 'number') {
    // iframe 高度与内容齐平时内部无滚动空间:滚轮链式交给聊天容器
    scrollAncestorBy(d.dy)
  } else if (d.type === 'prompt' && typeof d.prompt === 'string') {
    emit('prompt', d.prompt)
  }
}

function scrollAncestorBy(dy: number) {
  let el = iframeRef.value?.parentElement
  while (el && el !== document.body) {
    const st = getComputedStyle(el)
    if ((st.overflowY === 'auto' || st.overflowY === 'scroll') && el.scrollHeight > el.clientHeight + 1) {
      // 用派发原生 WheelEvent 触发浏览器默认滚动,与"光标在文字上滚"走同一路径,
      // 避免虚拟列表对程序化 scrollTop 改动的尺寸估算补偿(上滚被吃)。
      const before = el.scrollTop
      el.dispatchEvent(new WheelEvent('wheel', { deltaY: dy, bubbles: true, cancelable: true }))
      requestAnimationFrame(() => {
        if (el.scrollTop === before) el.scrollTop += dy
      })
      return
    }
    el = el.parentElement
  }
}

watch(() => props.path, load)
watch(() => theme.mode, (v) => {
  postToFrame({ type: 'theme', value: v })
})

onMounted(() => {
  window.addEventListener('message', onMessage)
  if (props.liveCode != null) {
    srcdoc.value = buildLiveShell()
    scheduleLive(props.liveCode)
  } else if (props.path) {
    load()
  }
})
onBeforeUnmount(() => {
  stopLiveTimer()
  window.removeEventListener('message', onMessage)
})
</script>

<template>
  <div class="widget-frame">
    <button
      v-if="path && !failed && srcdoc"
      class="wd-download"
      :class="{ busy: dlBusy }"
      :title="dlBusy ? '下载中…' : '下载组件(.html)'"
      @click="download"
    >
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
        <line x1="12" y1="4" x2="12" y2="15" /><polyline points="6 11 12 17 18 11" /><line x1="5" y1="20" x2="19" y2="20" />
      </svg>
    </button>
    <div v-if="failed" class="widget-failed">组件加载失败</div>
    <iframe
      v-else-if="srcdoc"
      ref="iframeRef"
      class="widget-iframe"
      sandbox="allow-scripts allow-popups"
      :srcdoc="srcdoc"
      :style="{ height: height + 'px' }"
      scrolling="no"
    ></iframe>
    <div v-else class="widget-loading">
      {{ liveCode != null ? '正在生成组件…' : '组件加载中…' }}
    </div>
  </div>
</template>

<style scoped>
.widget-frame {
  position: relative;
  width: 100%;
  max-width: 560px;
}
.widget-iframe {
  display: block;
  width: 100%;
  border: 1px solid var(--border-soft, #333);
  border-radius: 12px;
  background: transparent;
  transition: height 0.18s ease;
}
/* 下载:默认隐藏,悬停组件时右上角淡入一个轻量下载图标 */
.wd-download {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  color: var(--text-dim, #aaa);
  background: color-mix(in srgb, var(--bg-card, #222) 72%, transparent);
  backdrop-filter: blur(6px);
  border: 1px solid var(--border-soft, #444);
  border-radius: 6px;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s ease, color 0.15s ease, border-color 0.15s ease, background 0.15s ease;
}
.widget-frame:hover .wd-download {
  opacity: 1;
}
.wd-download:hover {
  color: var(--text-main, #eee);
  border-color: var(--primary, #7c8cff);
  background: color-mix(in srgb, var(--bg-card, #222) 90%, transparent);
}
.wd-download.busy {
  pointer-events: none;
  opacity: 0.5;
}
.widget-loading,
.widget-failed {
  padding: 16px;
  font-size: 12px;
  color: #999;
  text-align: center;
  border: 1px dashed var(--border-soft, #333);
  border-radius: 12px;
}
.widget-failed {
  color: var(--warn, #e6a23c);
}
</style>
