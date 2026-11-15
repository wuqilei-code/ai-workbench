# 给 AI 工作台加一个会说话的数字人助手：Vue 3 + Web Speech API 实战

> 关键词：Vue3、数字人、Web Speech API、语音交互、TTS、AI 工作台
> 适合读者：正在做 AI 对话产品、想加语音能力或虚拟形象的前端同学

## 一、背景

我们做的 AI 工作台（Vue 3 + Pinia + naive-ui）原本只有纯文本对话：用户提问，Agent 流式返回 Markdown，期间会调用工具、生成文件。

文本交互有两个体验缺口：

1. **入口太“工具化”**：新用户不知道该问什么，也不知道 AI 能做什么。
2. **长回复阅读成本高**：分析报告、周报这类内容，用户更希望“听”而不是“读”。

于是我们做了一个悬浮式数字人助手：平时待在右下角，点一下展开扇状菜单，可以语音提问、通话式连续对话、朗读回复、打开浏览器/文件面板、导出会话。它不是一个新的页面，而是叠加在现有聊天界面上的轻量交互层。

本文记录实现中的关键设计与踩过的坑，代码基于 Vue 3 `<script setup>` + TypeScript。

## 二、最终效果

- 角色本体：分层 WebP 素材（身体底图 + 左右眼 + 嘴），支持眨眼、说话口型、待机小动作。
- 五种状态：`idle / greet / listen / think / speak`，不同状态有不同漂浮、摇摆、光环动画。
- 点击角色：头顶扇形展开 7 个动作按钮（说话、通话、朗读、自动朗读、浏览器、导出、语音设置）。
- 语音输入：基于浏览器 `SpeechRecognition`，支持单次提问与免手通话模式。
- 语音输出：基于 `SpeechSynthesis`，按句流式朗读，可打断、可选音色/语速。
- 自动朗读：**默认关闭**，用户显式开启后才逐句朗读新回复，状态写入 `localStorage`。
- 降级策略：未检测到麦克风时，语音相关按钮直接置灰，悬浮提示跟随按钮显示。

> 配图建议：截一张数字人展开扇状菜单的图，再截一张通话中 + 波形动画的图。

## 三、技术方案总览

```
DigitalHuman.vue
├── 角色与动画层：WebP 分层 + CSS keyframes + 状态机
├── 交互层：扇状菜单（绝对定位 + transform 动画）
├── 语音输入：SpeechRecognition（Chrome/Edge）
├── 语音输出：SpeechSynthesis（按句切分、队列控制）
└── 设备能力检测：enumerateDevices + devicechange
```

它直接复用已有的 Pinia 聊天 store：

- `chat.isStreaming` / `chat.streamingText`：驱动思考/说话状态与状态文字；
- `chat.messages`：取最后一条 assistant 消息做朗读；
- `chat.sendMessage()` / `chat.abort()`：语音转文字后直接发消息；
- `chat.browserToolSignal`：语音指令“打开浏览器”直接唤出浏览器面板。

组件挂载在聊天布局里，全局只有一个实例：

```vue
<!-- ChatLayout.vue -->
<Onboarding ref="onboardingRef" />
<DigitalHuman />
```

## 四、素材分层：让一张静态图“活”起来

数字人素材一共 4 张，尺寸统一为 `300 × 776`、带透明通道的 WebP：

| 文件 | 用途 |
| --- | --- |
| `dh-body-base.webp` | 身体底图，脸部留空（没有眼睛和嘴巴） |
| `dh-eye-l.webp` / `dh-eye-r.webp` | 左右眼，叠加在底图上，用于眨眼 |
| `dh-mouth.webp` | 嘴部，用于说话口型 |

底图故意不画五官，眼睛和嘴通过绝对定位按百分比贴上去，这样换表情、做动画都不用重新出图：

```vue
<span class="dh-body">
  <img class="dh-img" :src="baseImg" alt="" />
  <img class="dh-part dh-p-l dh-eye" :class="{ blink }" :src="eyeLImg" />
  <img class="dh-part dh-p-r dh-eye" :class="{ blink }" :src="eyeRImg" />
  <img class="dh-part dh-p-m" :src="mouthImg" />
</span>
```

```css
.dh-part { position: absolute; pointer-events: none; }
.dh-p-l { left: 26%;    top: 22.16%; width: 18.67%; height: 6.44%; }
.dh-p-r { left: 55.33%; top: 22.16%; width: 18.67%; height: 6.44%; }
.dh-p-m { left: 42%;    top: 29.38%; width: 18.67%; height: 3.87%; }

.dh-eye { transform-origin: 50% 50%; }
.dh-eye.blink { animation: dhBlink .16s ease-in-out; }
@keyframes dhBlink {
  0%, 100% { transform: scaleY(1); }
  50%      { transform: scaleY(.06); }
}
```

眨眼用随机定时器驱动，间隔 2.6~6.8 秒，避免机械感：

```ts
function scheduleBlink() {
  window.clearTimeout(blinkTimer)
  blinkTimer = window.setTimeout(() => {
    blink.value = true
    window.setTimeout(() => { blink.value = false }, 160)
    scheduleBlink()
  }, 2600 + Math.random() * 4200)
}
```

## 五、状态机：一个 computed 管住所有动画

数字人的状态不需要 `watch` 去手动切换，而是从现有状态派生：

```ts
const state = computed<'idle' | 'greet' | 'listen' | 'think' | 'speak'>(() => {
  if (listening.value) return 'listen'
  if (greetActive.value) return 'greet'
  if (ttsSpeaking.value || (chat.isStreaming && !!chat.streamingText)) return 'speak'
  if (chat.isStreaming) return 'think'
  return 'idle'
})
```

模板上只挂一个 `data-state`：

```vue
<div class="dh-assistant" :data-state="state">
```

CSS 按状态切换不同动画与光环颜色：

```css
[data-state="listen"] .dh-body { animation: dhListen 1s ease-in-out infinite; }
[data-state="listen"] .dh-aura { background: radial-gradient(closest-side, rgba(61,220,151,.34), transparent 70%); }

[data-state="think"] .dh-body { animation: dhThink 2.4s ease-in-out infinite; }
[data-state="think"] .dh-aura { background: radial-gradient(closest-side, rgba(168,132,255,.32), transparent 70%); }

[data-state="speak"] .dh-body { animation: dhSpeak 1s ease-in-out infinite; }
[data-state="speak"] .dh-aura { background: radial-gradient(closest-side, rgba(91,120,255,.36), transparent 70%); }
```

另外还有待机随机小动作（转头、跳一下、摇摆、伸展），每 4~9 秒在 `idle` 状态下随机触发一次；同时尊重系统设置：

```css
@media (prefers-reduced-motion: reduce) {
  .dh-body, .dh-glow, .dh-ring, .dh-ring2, .dh-wavebar i, .dh-spark, .dh-fanbtn {
    animation: none !important;
  }
}
```

## 六、扇状菜单：用极坐标算位置

点击角色后，7 个动作按钮从头顶呈扇形展开。按钮本身是绝对定位的，位置用极坐标算出来，再把 `translate(-50%, -50%)` 和偏移拼到 `transform` 里：

```ts
const fanItems = computed(() => {
  const n = base.length
  const start = 90          // 从正上方开始
  const spread = 128        // 展开角度
  const R = 114             // 半径
  return base.map((it, i) => {
    const deg = start + (spread / (n - 1)) * i
    const a = deg * Math.PI / 180
    return { ...it, x: Math.cos(a) * R, y: -Math.sin(a) * R }
  })
})

function fanTransform(it: { x: number; y: number }) {
  return open.value
    ? `translate(-50%, -50%) translate(${it.x}px, ${it.y}px) scale(1)`
    : 'translate(-50%, -50%) scale(.3)'
}
```

进场用错峰延迟，形成“逐个弹出”的感觉：

```vue
:style="{ transitionDelay: (open ? i * 36 : 0) + 'ms' }"
```

## 七、语音识别：单次提问 + 通话模式

浏览器原生 `SpeechRecognition` 足够覆盖我们的场景（Chrome / Edge），不需要额外的 ASR 服务：

```ts
const SR = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition

rec = new SR()
rec.lang = 'zh-CN'
rec.interimResults = false
rec.continuous = false
rec.onresult = (e: any) => {
  const text = Array.from(e.results).map((r: any) => r[0].transcript).join('').trim()
  if (text) submit(text, true)
}
```

单次提问：点麦克风 → 识别结束 → 直接发消息。

通话模式：识别 `onend` 后自动重新监听，TTS 播报结束后再恢复监听，形成“说 → 答 → 听”的闭环：

```ts
function maybeResumeCall(delay = 700) {
  window.clearTimeout(callRestartTimer)
  if (!callMode.value) return
  callRestartTimer = window.setTimeout(() => {
    if (callMode.value && !listening.value && !chat.isStreaming && !ttsSpeaking.value) {
      startListening()
    }
  }, delay)
}
```

这里有两个容易忽略的前提：

1. `SpeechRecognition` 需要**安全上下文**（HTTPS 或 localhost），否则直接不可用；
2. 必须先成功拿到麦克风权限，否则 `start()` 会抛错，需要把错误码翻译成用户能看懂的提示（权限拒绝、无设备、无语音、网络问题等）。

## 八、流式 TTS：按句朗读，可打断

### 8.1 音色选择

浏览器返回的音色列表质量参差，我们做了一个简单的打分，优先选自然/神经网络音色：

```ts
function voiceScore(v: SpeechSynthesisVoice): number {
  const n = v.name.toLowerCase()
  let s = 0
  if (/natural|online|neural/.test(n)) s += 100
  if (/xiaoxiao|xiaoyi|yunxi|yunjian|yunyang|晓晓|云希|晓伊|晓萱/.test(n)) s += 80
  if (/google/.test(n)) s += 60
  if (/premium|enhanced|plus/.test(n)) s += 40
  if (v.lang === 'zh-CN') s += 20
  return s
}
```

`getVoices()` 是异步就绪的，首次可能为空，必须监听 `voiceschanged`：

```ts
window.speechSynthesis.addEventListener('voiceschanged', loadVoices)
```

### 8.2 按句切分，而不是等全文

AI 回复是流式的，如果等全文结束再朗读，用户要干等十几秒。我们监听最后一条 assistant 消息，每当出现完整句子就立刻朗读：

```ts
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
    const i = sentenceBoundary(seg)   // 找到最后一个句末标点
    if (i > 0) {
      const chunk = seg.slice(0, i).trim()
      if (chunk) speak(chunk)
      speakLen += i
    }
  }
}, { deep: true })
```

句末标点覆盖中英文：

```ts
const re = /[。！？!?；;.\n]/g
```

### 8.3 队列计数与打断

`speechSynthesis` 支持排队，但状态不能只看单条 utterance。我们用一个 `pending` 计数管理“是否正在说话”，任意一句结束都不会误判：

```ts
function speak(text: string) {
  if (!ttsSupported || !text) return
  const u = new SpeechSynthesisUtterance(text)
  if (currentVoice.value) u.voice = currentVoice.value
  u.lang = currentVoice.value?.lang || 'zh-CN'
  u.rate = rate.value

  pending++
  u.onstart = () => { ttsSpeaking.value = true }
  const done = () => {
    pending = Math.max(0, pending - 1)
    if (pending === 0) ttsSpeaking.value = false
  }
  u.onend = done
  u.onerror = done

  window.speechSynthesis.speak(u)
}

function stopSpeaking() {
  window.speechSynthesis.cancel()
  pending = 0
  ttsSpeaking.value = false
}
```

### 8.4 自动朗读默认关闭

最开始我们把“有回复就自动读”作为默认行为，结果一进页面就被声音“袭击”，非常劝退。最终改成显式开关：

```ts
const autoRead = ref(localStorage.getItem('dh_auto_read') === '1')  // 默认 false

function toggleAutoRead() {
  autoRead.value = !autoRead.value
  localStorage.setItem('dh_auto_read', autoRead.value ? '1' : '0')
  if (autoRead.value) {
    speakMsgId = null
    speakLen = 0
  } else {
    stopSpeaking()
  }
}
```

流式监听里加一道守卫，关掉时完全不产生任何语音：

```ts
if (!autoRead.value || !m || !ttsSupported) return
```

同时保留“朗读回复”单次按钮：自动朗读关掉，用户仍可以手动读最后一条回复。

## 九、麦克风可用性：先检测，再禁用，提示跟随按钮

只在点击后报“未检测到麦克风”体验很差。现在的做法是：**先枚举设备，没设备就把入口置灰**。

```ts
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
    // 无法枚举时按可用处理，点击后再走权限/设备错误提示
  }
}

onMounted(() => {
  detectMic()
  navigator.mediaDevices?.addEventListener?.('devicechange', detectMic)
})
```

监听 `devicechange` 的意义在于：用户中途插拔麦克风、或者在系统设置里禁用设备，界面能实时恢复/置灰，而不是必须刷新页面。

### 9.1 置灰但保留悬浮提示

原生 `disabled` 按钮不会触发鼠标事件，`title` 也不会显示，所以这里用 `aria-disabled` + 样式模拟禁用，点击时直接短路：

```vue
<button
  class="dh-fanbtn"
  :class="{ 'is-disabled': it.disabled }"
  :aria-disabled="it.disabled || undefined"
  @click="!it.disabled && it.act()"
>
  <svg class="dh-ico"><use :href="'#dh-ico-' + it.icon" /></svg>
  <!-- 提示锚定在按钮内部，随按钮位置移动 -->
  <span v-if="it.disabled" class="dh-tip">{{ t('human.noMicTip') }}</span>
</button>
```

```css
.dh-fan.on .dh-fanbtn.is-disabled { opacity: .38; cursor: not-allowed; }

.dh-tip {
  position: absolute; left: 50%; bottom: calc(100% + 7px);
  transform: translateX(-50%) translateY(3px);
  padding: 5px 9px; border-radius: 8px; white-space: nowrap;
  background: var(--bg-elev); border: 1px solid var(--dh-rule2);
  box-shadow: 0 10px 24px rgba(0,0,0,.45);
  opacity: 0; pointer-events: none; z-index: 9;
  transition: opacity .16s ease, transform .16s ease;
}

.dh-fanbtn.is-disabled:hover .dh-tip,
.dh-fanbtn.is-disabled:focus-visible .dh-tip {
  opacity: 1;
  transform: translateX(-50%) translateY(0);
}

/* 悬浮时提升层叠，避免被相邻按钮压住 */
.dh-fanbtn.is-disabled:hover { z-index: 10; }
```

注意：按钮本身带 `transform`，会形成独立的层叠上下文，提示的 `z-index` 只在按钮内部生效，所以需要给 hover 的按钮本身加 `z-index`。

### 9.2 不要写死在角色旁边

早期版本把“未检测到麦克风设备”写进了角色左侧的状态文字，结果不管提示哪个按钮，文案都固定在那一个位置，非常割裂。最终把提示放进按钮内部，天然跟随按钮的扇状位置移动。

## 十、菜单图标与设计系统对齐

最初的扇状按钮直接用 emoji（🎤 📞 🔊 🌐 ⬇ ⚙），和全站线性图标风格完全不搭。后来统一改成内联 SVG 精灵：

```vue
<svg class="dh-defs" aria-hidden="true">
  <defs>
    <symbol id="dh-ico-mic" viewBox="0 0 24 24">
      <path d="M12 2a2.5 2.5 0 0 0-2.5 2.5v7a2.5 2.5 0 0 0 5 0v-7A2.5 2.5 0 0 0 12 2z" />
      <path d="M19 10.5v1a7 7 0 0 1-14 0v-1" />
      <path d="M12 18.5V22M8.5 22h7" />
    </symbol>
    <!-- phone / volume / stop / repeat / globe / download / sliders ... -->
  </defs>
</svg>

<svg class="dh-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor"
     stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
  <use :href="'#dh-ico-' + it.icon" />
</svg>
```

规范：24×24 栅格、`stroke=currentColor`、线宽 1.8、圆头圆角，和输入框工具栏里的图标保持一致；颜色完全交给按钮的 `color`，active 时自动变成强调色。

按钮本身也补了键盘可达性：

```css
.dh-fanbtn:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
```

```vue
:aria-label="t(it.label)"
:aria-pressed="it.key === 'auto' ? autoRead : undefined"
```

## 十一、踩坑清单

1. **TTS 默认开启是大忌**：用户第一次打开就被朗读，先入为主的负反馈很强。默认关闭 + 显式开关更稳妥。
2. **`getVoices()` 首次为空**：必须监听 `voiceschanged`，不要只调一次。
3. **不要等全文再读**：按句切分才能跟上流式输出；同时要用计数器管理队列，避免某句结束就误判“已停止”。
4. **`SpeechRecognition` 只在安全上下文可用**：HTTP 局域网访问会直接失败，记得给用户明确提示。
5. **原生 `disabled` 与 tooltip 冲突**：禁用按钮不会触发 hover，也不会显示 `title`。用 `aria-disabled` + CSS 模拟。
6. **transform 会创建层叠上下文**：绝对定位的 tooltip 要显示在最上层，需要提升**按钮本身**的 `z-index`。
7. **设备检测要监听 `devicechange`**：否则插拔麦克风后状态不会更新，必须刷新页面。
8. **不要用常驻状态文字承载一次性信息**：例如交付文件名，展示一次就够；常驻会变成噪音。

## 十二、总结与后续

这个数字人助手的核心并不复杂，本质是三件事：

- **表现层**：分层素材 + CSS 状态机，低成本获得“活”的观感；
- **交互层**：语音输入/输出全部基于浏览器原生 API，零后端成本；
- **可用性**：默认关闭自动朗读、设备缺失时禁用入口、提示跟随控件、图标与设计系统对齐。

后续计划：

- 接入更高质量的服务端 TTS（音色统一、跨浏览器一致）；
- 数字人形象可配置（换装/换形象），并把语音设置持久化到用户偏好；
- 通话模式加入打断词（barge-in），支持用户随时插话；
- 为语音指令扩展更多本地意图，减少不必要的模型调用。

如果这篇文章对你有帮助，欢迎点赞收藏；有更好的数字人交互方案也欢迎在评论区交流。

---

> 仓库结构参考：
> `frontend/src/components/DigitalHuman.vue`、`frontend/src/views/ChatLayout.vue`、`frontend/src/i18n/index.ts`、`frontend/src/assets/dh-*.webp`
