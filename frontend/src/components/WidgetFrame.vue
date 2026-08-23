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
})();
<\/script>`

/** 文件模式:整页一次性渲染 */
function buildDoc(code: string): string {
  return (
    `<!doctype html><html data-widget-theme="${isDark() ? 'dark' : 'light'}"><head><meta charset="utf-8">` +
    `<meta name="viewport" content="width=device-width, initial-scale=1">` +
    `<style>html,body{margin:0;padding:0;background:transparent}</style></head><body>` +
    code +
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
  const stripped = stripScripts(code)
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
  } else if (d.type === 'prompt' && typeof d.prompt === 'string') {
    emit('prompt', d.prompt)
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
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
        <line x1="12" y1="4" x2="12" y2="15" /><polyline points="6 11 12 17 18 11" /><line x1="5" y1="20" x2="19" y2="20" />
      </svg>
      <span>下载</span>
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
}
.widget-iframe {
  display: block;
  width: 100%;
  border: 1px solid var(--border-soft, #333);
  border-radius: 12px;
  background: transparent;
  transition: height 0.18s ease;
}
.wd-download {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  font-size: 12px;
  line-height: 1;
  color: var(--text-dim, #aaa);
  background: color-mix(in srgb, var(--bg-card, #222) 78%, transparent);
  backdrop-filter: blur(6px);
  border: 1px solid var(--border-soft, #444);
  border-radius: 999px;
  cursor: pointer;
  opacity: 0.85;
  transition: opacity 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}
.widget-frame:hover .wd-download {
  opacity: 1;
}
.wd-download:hover {
  color: var(--text-main, #eee);
  border-color: var(--primary, #7c8cff);
}
.wd-download.busy {
  pointer-events: none;
  opacity: 0.6;
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
