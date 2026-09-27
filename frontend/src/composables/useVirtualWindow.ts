import { computed, onBeforeUnmount, shallowRef, type ComputedRef, type Ref } from 'vue'

/** 窗口化列表的最小元素:必须有稳定且唯一的 key(用于高度缓存与锚定) */
export interface VirtualWindowSource {
  key: string
}

export interface VirtualWindowRow<T> {
  key: string
  index: number
  item: T
}

export interface VirtualWindowOptions {
  /** 条数超过该阈值才启用窗口化;更短的会话整渲染,行为与未引入本模块时完全一致 */
  threshold?: number
  /** 视口上下各额外渲染的条数,避免快速滚动露白 */
  overscan?: number
  /** 未测量条目的初始估算高度(会随实测平均值收敛) */
  estimate?: number
  /** 高度变化时询问是否处于贴底状态:贴底则滚到底,否则按锚定补偿 */
  isPinnedToBottom?: () => boolean
}

/**
 * 变高列表的窗口化渲染(只挂载视口附近的行)。
 *
 * 设计取舍:
 * - **高度缓存以 key 为索引**:历史分页前插(loadOlderMessages)只改变顺序不改变 key,
 *   已测高度直接复用,无需重新测量。
 * - **未测量行按估算高度占位**,实测后通过 ResizeObserver 校正,并在必要时补偿 scrollTop:
 *   位于视口上方的行变高时补 +delta,保证用户正在阅读的内容不跳动;贴底时改为滚到底。
 * - **阈值兜底**:条数 ≤ threshold 时整渲染,短会话完全不受影响,避免为绝大多数场景引入回归。
 * - 容器需 `overflow-anchor: none`(ChatView 已设置),否则浏览器原生锚定会与本模块补偿叠加。
 */
export function useVirtualWindow<T extends VirtualWindowSource>(
  scrollRef: Ref<HTMLElement | null>,
  items: ComputedRef<T[]>,
  options: VirtualWindowOptions = {},
) {
  const threshold = options.threshold ?? 40
  const overscan = options.overscan ?? 6
  const baseEstimate = options.estimate ?? 240
  const isPinnedToBottom = options.isPinnedToBottom ?? (() => true)

  /** key → 实测高度 */
  const heights = new Map<string, number>()
  /** 触发重算的版本号(shallow:避免深度响应式代理的开销) */
  const version = shallowRef(0)
  const bump = () => { version.value++ }

  /** 视口滚动位置;由 setRef 的 ResizeObserver 与滚动事件共同维护 */
  const scrollTop = shallowRef(0)
  const viewportH = shallowRef(0)

  let ro: ResizeObserver | null = null
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver((entries) => {
      for (const e of entries) {
        const el = e.target as HTMLElement
        const key = el.dataset.vwKey
        if (!key) continue
        applyMeasured(key, el.offsetHeight)
      }
    })
  }

  /** 平均实测高度,用作未测量行的估算值(随样本收敛) */
  const averageHeight = (): number => {
    if (!heights.size) return baseEstimate
    let sum = 0
    for (const h of heights.values()) sum += h
    const avg = sum / heights.size
    // 限制在 [80, 1200],避免个别超大行把估算拉偏
    return Math.min(1200, Math.max(80, avg))
  }

  /** 累计偏移:offsets[i] 是第 i 行的顶部坐标,offsets[n] 是总高度 */
  const offsets = computed<number[]>(() => {
    void version.value
    const list = items.value
    const out = new Array<number>(list.length + 1)
    const est = averageHeight()
    let acc = 0
    for (let i = 0; i < list.length; i++) {
      out[i] = acc
      acc += heights.get(list[i].key) ?? est
    }
    out[list.length] = acc
    return out
  })

  const totalHeight = computed(() => offsets.value[items.value.length] ?? 0)

  const windowed = computed(() => items.value.length > threshold)

  /** 可见区间 [start, end) —— 二分查找首行,再线性推进 */
  const range = computed<{ start: number; end: number }>(() => {
    void version.value
    const n = items.value.length
    if (!windowed.value || n === 0) return { start: 0, end: n }
    const off = offsets.value
    const top = scrollTop.value
    const bottom = top + viewportH.value
    // 找第一行:其底部 > top
    let lo = 0
    let hi = n
    while (lo < hi) {
      const mid = (lo + hi) >> 1
      if (off[mid + 1] <= top) lo = mid + 1
      else hi = mid
    }
    const start = Math.max(0, lo - overscan)
    let end = lo
    while (end < n && off[end] < bottom) end++
    end = Math.min(n, end + overscan)
    return { start, end: Math.max(end, Math.min(n, lo + 1)) }
  })

  const rows = computed<VirtualWindowRow<T>[]>(() => {
    const list = items.value
    const { start, end } = range.value
    const out: VirtualWindowRow<T>[] = []
    for (let i = start; i < end; i++) out.push({ key: list[i].key, index: i, item: list[i] })
    return out
  })

  /** 视口上方/下方的占位高度 */
  const topSpacer = computed(() => {
    if (!windowed.value) return 0
    return offsets.value[range.value.start] ?? 0
  })
  const bottomSpacer = computed(() => {
    if (!windowed.value) return 0
    const off = offsets.value
    return Math.max(0, (off[items.value.length] ?? 0) - off[range.value.end])
  })

  /** 记录一次实测高度,并在需要时补偿滚动锚点 */
  function applyMeasured(key: string, h: number) {
    if (!(h > 0)) return
    const prev = heights.get(key)
    if (prev !== undefined && Math.abs(prev - h) < 0.5) return
    const el = scrollRef.value
    const idx = indexOfKey(key)
    const wasAboveViewport = el != null && idx >= 0
      ? (offsets.value[idx + 1] ?? 0) <= el.scrollTop
      : false
    heights.set(key, h)
    bump()
    if (!el || prev === undefined) return
    const delta = h - prev
    if (Math.abs(delta) < 0.5) return
    if (isPinnedToBottom()) {
      el.scrollTop = el.scrollHeight
    } else if (wasAboveViewport) {
      // 该行整体位于视口上方:向下补偿,视口内容保持不动
      el.scrollTop += delta
    }
  }

  function indexOfKey(key: string): number {
    const list = items.value
    for (let i = 0; i < list.length; i++) if (list[i].key === key) return i
    return -1
  }

  /** 行元素的 ref 回调:挂载时开始观察,卸载时停止 */
  function setRowRef(key: string, el: Element | null) {
    if (!ro) return
    if (el) {
      el.setAttribute('data-vw-key', key)
      ro.observe(el)
    }
  }

  /** 读取当前视口几何(滚动事件与挂载后各调一次) */
  function syncViewport() {
    const el = scrollRef.value
    if (!el) return
    if (el.scrollTop !== scrollTop.value) scrollTop.value = el.scrollTop
    if (el.clientHeight !== viewportH.value) viewportH.value = el.clientHeight
  }

  /** 行未渲染时也能定位:按偏移直接设置 scrollTop(供会话内查找使用) */
  function scrollToKey(key: string, block: 'start' | 'center' = 'start') {
    const el = scrollRef.value
    const idx = indexOfKey(key)
    if (!el || idx < 0) return false
    const top = offsets.value[idx] ?? 0
    const h = heights.get(key) ?? averageHeight()
    const target = block === 'center' ? top - Math.max(0, (el.clientHeight - h) / 2) : top
    el.scrollTop = Math.max(0, target)
    syncViewport()
    return true
  }

  /** 该 key 当前是否已渲染(供查找判断能否用 DOM 定位) */
  function isRendered(key: string): boolean {
    return rows.value.some((r) => r.key === key)
  }

  /** 会话切换:清空高度缓存与视口几何 */
  function reset() {
    heights.clear()
    scrollTop.value = 0
    viewportH.value = 0
    bump()
  }

  /** 丢弃已不在列表中的 key 的高度,避免长时间使用后缓存无限增长 */
  function pruneHeights() {
    if (heights.size <= items.value.length * 2 + 64) return
    const alive = new Set(items.value.map((i) => i.key))
    for (const k of Array.from(heights.keys())) if (!alive.has(k)) heights.delete(k)
  }

  onBeforeUnmount(() => {
    ro?.disconnect()
    ro = null
  })

  return {
    windowed,
    rows,
    topSpacer,
    bottomSpacer,
    totalHeight,
    setRowRef,
    syncViewport,
    scrollToKey,
    isRendered,
    reset,
    pruneHeights,
  }
}
