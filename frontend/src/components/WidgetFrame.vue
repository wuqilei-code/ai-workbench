<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useMessage } from 'naive-ui'
import { fileApi } from '@/api/files'
import { useThemeStore } from '@/stores/theme'
import { SVG_LINE_FILL_FIX_CSS } from '@/utils/svgFix'

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

const msg = useMessage()
const theme = useThemeStore()
const srcdoc = ref('')
const failed = ref(false)
const height = ref(220)
const iframeRef = ref<HTMLIFrameElement | null>(null)
const dlBusy = ref(false)
/** 组件原始代码(供 复制代码/查看代码/下载为图片 使用) */
const rawCode = ref('')
/** "展示详情"折叠状态:默认展开 */
const expanded = ref(true)
/** 代码查看面板开关 */
const showCode = ref(false)
/** 图片导出进行中(隐藏捕获 iframe 渲染中) */
const capBusy = ref(false)

function isDark(): boolean {
  return theme.mode === 'dark'
}

/** 流式期间移除 script 块(含尾部未闭合的),JS 图表等定稿后由文件版组件统一执行 */
function stripScripts(code: string): string {
  return code.replace(/<script\b[\s\S]*?<\/script\s*>/gi, '').replace(/<script\b[\s\S]*$/i, '')
}

/** 检测 widget 代码是否自身支持暗色主题。
 *  - data-widget-theme:显式主题门控(提示词契约),认定支持;
 *  - prefers-color-scheme:必须出现在 <style> 块内才真正生效 —— 仅在注释/字符串里
 *    出现时不应判定支持,否则硬编码白底的 SVG 流程图会跳过反色兜底,暗色下刺眼。 */
function hasThemeSupport(code: string): boolean {
  if (/data-widget-theme/.test(code)) return true
  const styles = Array.from(code.matchAll(/<style\b[\s\S]*?<\/style\s*>/gi))
    .map((m) => m[0])
    .join('\n')
  return /prefers-color-scheme/.test(styles)
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
    if(d.type==='nodark'&&document.documentElement){
      /* enable=false 时移除反色(切回亮色/内容本身有暗色支持),否则启用 */
      if(d.enable===false){document.documentElement.removeAttribute('data-aiwb-nodark')}
      else{document.documentElement.setAttribute('data-aiwb-nodark','1')}
      return}
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
    requestAnimationFrame(function(){fixSvg();reportH()});
  }
  function reportH(){send({type:'height',height:Math.ceil(document.documentElement.getBoundingClientRect().height)})}
  /* AI 生成的 SVG viewBox 尺寸常与内容不匹配(如 viewBox 高 1180 但内容到 y=1664),
   * 超出部分被裁掉表现为"长图展示不完整"。按 getBBox() 实测内容包围盒自适应扩展 viewBox。 */
  function fixSvg(){
    var list=document.querySelectorAll('svg[viewBox]');
    for(var i=0;i<list.length;i++){
      var s=list[i];
      try{
        var b=s.getBBox();
        if(!b||!isFinite(b.width)||!isFinite(b.height)||b.width<=0||b.height<=0)continue;
        var vb=(s.getAttribute('viewBox')||'').split(/[\\s,]+/);
        if(vb.length!==4)continue;
        var x=parseFloat(vb[0]),y=parseFloat(vb[1]),w=parseFloat(vb[2]),h=parseFloat(vb[3]);
        if(!isFinite(x)||!isFinite(y)||!isFinite(w)||!isFinite(h)||w<=0||h<=0)continue;
        var p=2,nx=Math.min(x,b.x-p),ny=Math.min(y,b.y-p);
        var nw=Math.max(x+w,b.x+b.width+p)-nx,nh=Math.max(y+h,b.y+b.height+p)-ny;
        if(nw>w+0.5||nh>h+0.5||nx<x-0.5||ny<y-0.5){s.setAttribute('viewBox',nx+' '+ny+' '+nw+' '+nh)}
      }catch(e){}
    }
  }
  if(document.fonts&&document.fonts.ready){document.fonts.ready.then(function(){fixSvg();reportH()})}
  if('ResizeObserver' in window){new ResizeObserver(function(){fixSvg();reportH()}).observe(document.documentElement)}
  window.addEventListener('load',function(){fixSvg();reportH();send({type:'ready'})});
  setTimeout(function(){fixSvg();reportH();send({type:'ready'})},600);
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

/**
 * 主题上下文兜底样式(注入壳 <head>):
 * - 设定 color-scheme,让原生控件/滚动条跟随 App 主题;
 * - 提供标准令牌的暗/亮默认值(:root[data-widget-theme=...])。当某 widget 只改背景/未自定
 *   义文字令牌、或暗色块没覆盖到所有文字时,兜底保证文字继承正确的可读前景色,避免
 *   "背景变深但字色不清" 的情况。widget 自身显式指定的颜色优先,不受此覆盖。
 * - 让 body/根的文字颜色回退到主题前景色,text-muted 使用主题次级前景。
 */
function themeFallback(): string {
  const fallback = (scheme: 'light' | 'dark') =>
    `:root[data-widget-theme="${scheme}"]{color-scheme:${scheme};` +
    (scheme === 'dark'
      ? '--text:#E5E5E5;--text-muted:#A1A1AA;--surface:#171717;--surface-muted:#1F1F1F;--border:#333;'
      : '--text:#171717;--text-muted:#71717A;--surface:#F7F7F8;--surface-muted:#ECECEF;--border:#D9D9DE;') +
    `}`
  return (
    `<style>` +
    /* 若无任何 data-widget-theme 门控(老 widget),也按 App 当前主题提供可读前景 */
    `:root{color-scheme:${isDark() ? 'dark' : 'light'}}` +
    fallback(isDark() ? 'dark' : 'light') +
    `html,body{margin:0;padding:0;background:transparent;color:var(--text,#E5E5E5)}` +
    /* 无暗色支持(nodark)的硬编码浅色内容(SVG 流程图等)整体反色+色相旋转兜底:
     * 作用于 body 级,不依赖 widget 自带 data-dynamic-ui-widget 属性;文件/流式两模式共用 */
    `:root[data-aiwb-nodark="1"] body{filter:invert(1) hue-rotate(180deg)}` +
    `</style>`
  )
}

function buildDoc(code: string): string {
  const themeVal = isDark() ? 'dark' : 'light'
  // 无暗色支持的 widget:html 预置 nodark 属性,反色规则由 themeFallback() 统一提供
  const needsInvert = isDark() && !hasThemeSupport(code)
  const nodarkAttr = needsInvert ? ' data-aiwb-nodark="1"' : ''
  return (
    `<!doctype html><html data-widget-theme="${themeVal}"${nodarkAttr}><head><meta charset="utf-8">` +
    `<meta name="viewport" content="width=device-width, initial-scale=1">` +
    `<style>html,body{margin:0;padding:0;background:transparent}</style>` +
    `<style>${SVG_LINE_FILL_FIX_CSS}</style>` +
    themeFallback() +
    `</head><body>` +
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
let _liveDarkChecked = false

function buildLiveShell(): string {
  return (
    `<!doctype html><html data-widget-theme="${isDark() ? 'dark' : 'light'}"><head><meta charset="utf-8">` +
    `<meta name="viewport" content="width=device-width, initial-scale=1">` +
    `<style>html,body{margin:0;padding:0;background:transparent}</style>` +
    `<style>${SVG_LINE_FILL_FIX_CSS}</style>` +
    themeFallback() +
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
  // 流式期间首次收到足够长代码时检测暗色支持;若无,通知壳添加反色滤镜
  // (阈值取 200:流式早期 <style> 尚未生成,过早检测会把"稍后有暗色规则"误判为不支持)
  if (isDark() && !_liveDarkChecked && code.trim().length > 200) {
    _liveDarkChecked = true
    if (!hasThemeSupport(code)) {
      postToFrame({ type: 'nodark' })
    }
  }
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

/** 失败占位展示用的文件名(末段,避免长路径) */
const failName = computed(() => (props.path || '').split(/[\\/]/).pop() || 'widget')

async function load() {
  failed.value = false
  srcdoc.value = ''
  rawCode.value = ''
  showCode.value = false
  _liveDarkChecked = false
  try {
    const raw = await fileApi.content(props.path || '')
    if (!raw) throw new Error('empty')
    rawCode.value = String(raw)
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

// ---------- ⋯ 菜单:下载 / 图片导出 / 复制 / 查看代码 ----------

const menuOpen = ref(false)
const frameRef = ref<HTMLElement | null>(null)
function toggleMenu() {
  menuOpen.value = !menuOpen.value
}
function onDocClick(e: MouseEvent) {
  if (!menuOpen.value) return
  const el = e.target as Element | null
  if (el?.closest?.('.wd-menu-wrap')) return
  menuOpen.value = false
}

async function copyCode() {
  menuOpen.value = false
  if (!rawCode.value) return
  try {
    await navigator.clipboard.writeText(rawCode.value)
    msg.success('代码已复制')
  } catch {
    msg.error('复制失败')
  }
}

function toggleCodeView() {
  menuOpen.value = false
  showCode.value = !showCode.value
}

/**
 * 组件代码 → PNG(纯前端,无第三方库):
 * 片段代码经 DOMParser 规范化后包进 SVG foreignObject,绘入 canvas 导出。
 * 注意:DOMParser 会把片段开头的 <style> 提升到 <head>,必须把 head 里的样式
 * 一并搬进 foreignObject,否则组件失去全部 CSS(图形按默认黑色填充、文字消失)。
 * 脚本不执行(Canvas 类图表落到 HTML 兜底内容,符合 SVG-first 技能契约);
 * :root 主题门控重写为 #wroot,让令牌与暗色覆盖在片段环境内继续生效。
 */
async function captureToImage() {
  menuOpen.value = false
  if (!rawCode.value || capBusy.value) return
  capBusy.value = true
  try {
    const url = await widgetPngDataUrl(rawCode.value)
    const a = document.createElement('a')
    a.href = url
    const base = (props.path || 'widget').split(/[\\/]/).pop() || 'widget'
    a.download = base.replace(/\.html?$/i, '') + '.png'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
  } catch {
    msg.error('导出图片失败(组件可能含外部资源)')
  } finally {
    capBusy.value = false
  }
}

const CAP_SCALE = 2
const CAP_MAX_H = 2600

function widgetPngDataUrl(code: string): Promise<string> {
  const stripped = stripScripts(normalizeWidgetTheme(code)).replace(/:root\b/g, '#wroot')
  const doc = new DOMParser().parseFromString(stripped, 'text/html')
  // 关键:样式被 DOMParser 放进 head,需显式取回并置于内容之前
  const styles = Array.from(doc.head.querySelectorAll('style'))
    .map((n) => n.textContent || '')
    .join('\n')
    // style 文本作为 XML 内容需转义,否则 CSS 里的 < / & 会让 SVG 解析失败
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
  const inner = new XMLSerializer().serializeToString(doc.body)
  const scheme = isDark() ? 'dark' : 'light'
  // 导出宽度取组件实际显示宽度,做到所见即所得
  const capW = Math.max(
    320,
    Math.round(frameRef.value?.clientWidth || iframeRef.value?.clientWidth || 640),
  )
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="${capW}" height="${CAP_MAX_H}">` +
    `<foreignObject width="100%" height="100%">` +
    `<div xmlns="http://www.w3.org/1999/xhtml" id="wroot" data-widget-theme="${scheme}" ` +
    `style="width:${capW}px;` +
    `font-family:system-ui,-apple-system,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif">` +
    // 主题令牌兜底在前,组件自身样式在后(组件优先);:root 需改写为 #wroot 才能在 SVG 环境命中
    `<style>${themeFallback().replace(/:root\b/g, '#wroot')}#wroot{color:${scheme === 'dark' ? '#E5E5E5' : '#171717'}}` +
    `#wroot>*{max-width:100%}</style>` +
    (styles ? `<style>${styles}</style>` : '') +
    inner +
    `</div></foreignObject></svg>`
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => {
      try {
        const cv = document.createElement('canvas')
        cv.width = capW * CAP_SCALE
        cv.height = CAP_MAX_H * CAP_SCALE
        const ctx = cv.getContext('2d', { willReadFrequently: true })
        if (!ctx) throw new Error('no ctx')
        ctx.drawImage(img, 0, 0, cv.width, cv.height)
        const data = ctx.getImageData(0, 0, cv.width, cv.height).data
        // 从底部向上找第一行含不透明像素的行 = 内容实际底部
        let bottom = 0
        outer: for (let y = cv.height - 1; y >= 0; y--) {
          const row = y * cv.width * 4
          for (let x = 0; x < cv.width; x++) {
            if (data[row + x * 4 + 3] > 0) { bottom = y + 1; break outer }
          }
        }
        if (bottom <= 0) throw new Error('empty render')
        const out = document.createElement('canvas')
        out.width = cv.width
        out.height = Math.min(bottom + 16 * CAP_SCALE, cv.height)
        const octx = out.getContext('2d')
        if (!octx) throw new Error('no ctx')
        octx.fillStyle = scheme === 'dark' ? '#17171A' : '#FFFFFF'
        octx.fillRect(0, 0, out.width, out.height)
        octx.drawImage(cv, 0, 0, out.width, out.height, 0, 0, out.width, out.height)
        resolve(out.toDataURL('image/png'))
      } catch (e) {
        reject(e)
      }
    }
    img.onerror = () => reject(new Error('svg load failed'))
    img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg)
  })
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
    // 上限放宽到 8000:长流程图等高内容经 viewBox 自适应后可能超过 3000
    height.value = Math.max(80, Math.min(8000, d.height))
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
      const target = el
      el.dispatchEvent(new WheelEvent('wheel', { deltaY: dy, bubbles: true, cancelable: true }))
      requestAnimationFrame(() => {
        if (target.scrollTop === before) target.scrollTop += dy
      })
      return
    }
    el = el.parentElement
  }
}

watch(() => props.path, load)
watch(() => theme.mode, (v) => {
  postToFrame({ type: 'theme', value: v })
  // nodark 反色兜底随主题实时启停,无需刷新页面:
  // 按当前内容重新判定"无暗色支持",dark→启用反色,light→移除
  const code = (props.liveCode != null ? _pendingLive : rawCode.value) || ''
  if (code.trim().length > 200) {
    postToFrame({ type: 'nodark', enable: v === 'dark' && !hasThemeSupport(code) })
  }
})

onMounted(() => {
  window.addEventListener('message', onMessage)
  document.addEventListener('click', onDocClick)
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
  document.removeEventListener('click', onDocClick)
})
</script>

<template>
  <div ref="frameRef" class="widget-frame" :class="{ bare: !path }">
    <!-- 头部:展示详情(点击折叠)+ ⋯ 操作菜单;流式生成中(live)不显示 -->
    <div v-if="path && !failed" class="wd-head">
      <button class="wd-head-toggle" @click="expanded = !expanded">
        <svg
          class="wd-chev"
          :class="{ up: !expanded }"
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2.4"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <polyline points="6 9 12 15 18 9" />
        </svg>
        <span>展示详情</span>
      </button>
      <div class="wd-menu-wrap">
        <button
          class="wd-menu-btn"
          :class="{ open: menuOpen }"
          title="更多操作"
          @click.stop="toggleMenu"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
            <circle cx="5" cy="12" r="1.7" /><circle cx="12" cy="12" r="1.7" /><circle cx="19" cy="12" r="1.7" />
          </svg>
        </button>
        <div v-if="menuOpen" class="wd-menu" @click.stop>
          <button class="wd-menu-item" :disabled="dlBusy" @click="download">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" />
            </svg>
            <span>下载到本地</span>
          </button>
          <button class="wd-menu-item" :disabled="capBusy || !rawCode" @click="captureToImage">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><circle cx="8.5" cy="8.5" r="1.5" /><polyline points="21 15 16 10 5 21" />
            </svg>
            <span>{{ capBusy ? '正在生成图片…' : '下载为图片' }}</span>
          </button>
          <button class="wd-menu-item" :disabled="!rawCode" @click="copyCode">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="9" y="9" width="13" height="13" rx="2" ry="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
            </svg>
            <span>复制代码</span>
          </button>
          <button class="wd-menu-item" :disabled="!rawCode" @click="toggleCodeView">
            <svg v-if="!showCode" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="16 18 22 12 16 6" /><polyline points="8 6 2 12 8 18" />
            </svg>
            <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><line x1="3" y1="9" x2="21" y2="9" />
            </svg>
            <span>{{ showCode ? '显示UI' : '查看代码' }}</span>
          </button>
        </div>
      </div>
    </div>

    <div v-if="failed" class="widget-failed">
      <div class="wf-fail-t">组件已不可用</div>
      <div class="wf-fail-s">
        文件 <code>{{ failName }}</code> 不存在,可能已在文件树中被删除或移动;让 AI 重新生成一次即可恢复。
      </div>
    </div>
    <template v-else-if="expanded">
      <!-- 代码视图与 UI 视图互斥:查看代码时直接在同一区域显示源码 -->
      <pre v-if="showCode && rawCode" class="wd-code"><code>{{ rawCode }}</code></pre>
      <template v-else>
        <iframe
          v-if="srcdoc"
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
      </template>
    </template>
  </div>
</template>

<style scoped>
.widget-frame {
  position: relative;
  width: 100%;
  border: 1px solid var(--border-soft, #333);
  border-radius: 12px;
  background: var(--surface, transparent);
  overflow: visible;
}
/* 流式 live 模式与失败态:保持旧版无边框轻量外观 */
.widget-frame.bare {
  border: none;
  background: transparent;
}
.widget-frame:not(.bare) .widget-iframe {
  border: none;
  border-radius: 0 0 11px 11px;
}
/* 头部:展示详情 + ⋯ */
.wd-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px 6px 10px;
  border-bottom: 1px solid var(--border-soft, #333);
}
.wd-head-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: transparent;
  color: var(--text, #ddd);
  font-size: 13px;
  font-weight: 500;
  padding: 4px 6px;
  border-radius: 6px;
  cursor: pointer;
}
.wd-head-toggle:hover { background: var(--surface-hover, rgba(255, 255, 255, 0.05)); }
.wd-chev { transition: transform 0.15s ease; color: var(--text-3, #999); }
.wd-chev.up { transform: rotate(-90deg); }
.wd-menu-wrap { position: relative; }
.wd-menu-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-3, #999);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.wd-menu-btn:hover,
.wd-menu-btn.open {
  background: var(--surface-hover, rgba(255, 255, 255, 0.07));
  color: var(--text, #ddd);
}
.wd-menu {
  position: absolute;
  top: calc(100% + 4px);
  right: 0;
  z-index: 30;
  min-width: 148px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px;
  border: 1px solid var(--border, #444);
  border-radius: 10px;
  background: var(--bg-elev, #23262d);
  box-shadow: var(--shadow, 0 6px 24px rgba(0, 0, 0, 0.35));
}
.wd-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 9px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--text, #ddd);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  white-space: nowrap;
}
.wd-menu-item svg { flex-shrink: 0; color: var(--text-3, #999); }
.wd-menu-item:hover { background: var(--surface-hover, rgba(255, 255, 255, 0.07)); }
.wd-menu-item:disabled { opacity: 0.5; pointer-events: none; }
.widget-iframe {
  display: block;
  width: 100%;
  border: 1px solid var(--border-soft, #333);
  border-radius: 12px;
  background: transparent;
  transition: height 0.18s ease;
}
.wd-code {
  margin: 0;
  max-height: 320px;
  overflow: auto;
  padding: 10px 12px;
  border-top: 1px solid var(--border-soft, #333);
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-2, #bbb);
  background: var(--code-bg, #1e1e1e);
  border-radius: 0 0 11px 11px;
  white-space: pre-wrap;
  word-break: break-all;
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
.widget-frame:not(.bare) .widget-loading { border: none; }
.widget-failed {
  color: var(--warn, #e6a23c);
}
.widget-failed .wf-fail-t { font-size: 13px; font-weight: 600; margin-bottom: 4px; }
.widget-failed .wf-fail-s { color: var(--text-2, #bbb); line-height: 1.6; }
.widget-failed code {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 11px;
  background: var(--surface-hover, rgba(255, 255, 255, 0.06));
  padding: 1px 5px;
  border-radius: 4px;
}
</style>
