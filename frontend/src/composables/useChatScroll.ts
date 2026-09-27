import { ref, computed, watch, nextTick, type Ref, type ComputedRef } from 'vue'
import { extractPlan } from '@/utils/turns'
import type { useChatStore } from '@/stores/chat'

type ChatStore = ReturnType<typeof useChatStore>

/**
 * 消息列表的滚动/钉底/上翻加载逻辑。
 * <p>
 * 从 ChatView 抽出。核心规则:
 * - 贴底(stickToBottom)时流式输出自动跟随;用户上翻阅读时不拉回;
 * - 打开会话后有 1s「贴底稳定期」,忽略异步测量引发的瞬时非贴底事件,避免"先到底又自动上滚";
 * - 内容撑高(图片加载等)不释放钉底;
 * - 滚到顶自动加载更早分页,前插期间(backfilling)抑制自动滚底。
 *
 * @param scrollRef     滚动容器 ref
 * @param virtualItems  虚拟列表项(为空时不滚动)
 */
export function useChatScroll(
  chat: ChatStore,
  scrollRef: Ref<HTMLElement | null>,
  virtualItems: ComputedRef<unknown[]>,
) {
  /** 用户是否停留在底部区域(阈值内视为贴底) */
  const stickToBottom = ref(true)
  /** 正在前插更早分页:期间抑制自动滚底 */
  const isBackfilling = ref(false)

  let openSettleUntil = 0
  let prevScrollHeight = 0

  function scrollToBottom() {
    nextTick(() => {
      const el = scrollRef.value
      if (!el) return
      if (!virtualItems.value.length) return
      el.scrollTop = el.scrollHeight
    })
  }

  /** 同步滚到底(会话切换:遮罩摘掉、列表刚挂载的那一帧立即定位,避免"先顶部后底部"闪烁) */
  function scrollToBottomNow() {
    const el = scrollRef.value
    if (!el) return
    if (!virtualItems.value.length) return
    el.scrollTop = el.scrollHeight
  }

  /** 打开会话后的贴底稳定期入口:强制钉底并在异步测量收尾时持续回钉 */
  function pinOnOpen() {
    stickToBottom.value = true
    openSettleUntil = Date.now() + 1000
    const el = scrollRef.value
    if (el) prevScrollHeight = el.scrollHeight
    scrollToBottomNow()
    const tick = () => {
      scrollToBottomNow()
      if (Date.now() < openSettleUntil) requestAnimationFrame(tick)
    }
    requestAnimationFrame(tick)
    setTimeout(scrollToBottomNow, 200)
    setTimeout(scrollToBottomNow, 500)
  }

  // 会话切换完成(列表重新挂载)的同一帧立即滚到底,并用 rAF + 延迟消除"闪一下顶部"
  watch(
    () => chat.switching,
    (now, prev) => {
      if (!now && prev) pinOnOpen()
    },
    { flush: 'post' },
  )

  // 提交/拒绝权限后 agent 继续输出:把用户拉回底部(nextTick 可能早于虚拟列表渲染,延迟再滚)
  watch(
    () => chat.isStreaming,
    (now, prev) => {
      if (now && !prev) {
        stickToBottom.value = true
        scrollToBottom()
        setTimeout(scrollToBottom, 200)
        setTimeout(scrollToBottom, 600)
      }
    },
  )

  // 仅贴底时跟随流式增量(交给 vueuc 内置 ResizeObserver,避免与强制滚动竞争抖动)
  watch(
    () => chat.streamingText,
    () => {
      if (stickToBottom.value) scrollToBottom()
    },
  )

  // 前插更早分页 / 用户上翻时不自动滚底
  watch(
    () => chat.messages.length,
    () => {
      if (isBackfilling.value || chat.loadingOlder) return
      if (!stickToBottom.value) return
      // 打开历史会话 / 压缩后刷新:等渲染后再滚到底(高度异步实测,需延迟多次)
      scrollToBottom()
      setTimeout(scrollToBottom, 150)
      setTimeout(scrollToBottom, 400)
    },
  )
  watch(
    () => chat.turns.length,
    () => {
      if (isBackfilling.value || chat.loadingOlder) return
      if (!stickToBottom.value) return
      scrollToBottom()
    },
  )

  /** 滚动事件:更新贴底状态 + 滚到顶时加载更早历史 */
  function handleVirtualScroll() {
    const el = scrollRef.value
    if (!el) return
    // 贴底稳定期:瞬时非贴底事件不误判,否则内容撑高后不再回钉 → "先到底又自动向上滚"
    if (Date.now() < openSettleUntil) {
      stickToBottom.value = true
      return
    }
    // 内容撑高(图片加载等):scrollHeight 增长说明是内容变化而非用户上翻,不释放钉底
    if (el.scrollHeight > prevScrollHeight) {
      prevScrollHeight = el.scrollHeight
      return
    }
    prevScrollHeight = el.scrollHeight
    stickToBottom.value = el.scrollTop + el.clientHeight >= el.scrollHeight - 120
    // 滚到顶:加载更早历史(loadingOlder 防重入;hasOlder=false 表示已到最早)
    if (el.scrollTop <= 60 && chat.hasOlder && !chat.loadingOlder) {
      isBackfilling.value = true
      // 前插锚定:记录插入前的滚动高度/位置,插入完成后按高度差补偿 scrollTop,
      // 否则无论普通滚动还是窗口化(未渲染行用占位高度估算),视口都会整体上跳一屏。
      const anchorTop = el.scrollTop
      const anchorHeight = el.scrollHeight
      // nextTick 后复位:store 的 loadingOlder 同步复位,而 messages/turns 的 watch
      // 在组件下一帧调度,届时标志需仍为 true 才能抑制"前插后自动滚底"
      chat
        .loadOlderMessages()
        .catch(() => {})
        .finally(() =>
          nextTick(() => {
            const cur = scrollRef.value
            if (cur) {
              cur.scrollTop = anchorTop + (cur.scrollHeight - anchorHeight)
              prevScrollHeight = cur.scrollHeight
            }
            isBackfilling.value = false
          }),
        )
    }
  }

  /** 当前生成中 turn 的任务规划:悬浮在输入框上方,随 write_todos 实时刷新;
   *  生成结束(完成/出错/中断)即隐藏——完成态由结果文件卡与正文承载。 */
  const activePlan = computed(() => {
    if (!chat.isStreaming || !chat.turns.length) return []
    return extractPlan(chat.turns[chat.turns.length - 1])
  })
  // 任务栏出现/步骤变化会改变消息可视高度:贴底时重新滚到底,避免最新内容被任务栏盖住
  watch(
    () => activePlan.value.length,
    () => {
      if (stickToBottom.value) {
        scrollToBottom()
        setTimeout(scrollToBottom, 60)
      }
    },
  )

  /**
   * 监听内容整体包装层高度变化(图片/iframe 异步撑高):撑高时若贴底则跟随,
   * 避免"图渲染完视图向上滚、看不到最新输出"。须在列表挂载后调用,返回清理函数。
   * 注意观察的是内容包装层而非滚动容器(容器尺寸固定不触发)。
   */
  function observeContentGrowth(scroller: HTMLElement | null, inner: HTMLElement | null) {
    if (!scroller || !inner || typeof ResizeObserver === 'undefined') {
      return () => {}
    }
    prevScrollHeight = scroller.scrollHeight
    const ro = new ResizeObserver(() => {
      const prev = prevScrollHeight
      prevScrollHeight = scroller.scrollHeight
      if (scroller.scrollHeight > prev && stickToBottom.value) {
        scroller.scrollTop = scroller.scrollHeight
      }
    })
    ro.observe(inner)
    return () => ro.disconnect()
  }

  return {
    stickToBottom,
    isBackfilling,
    activePlan,
    scrollToBottom,
    scrollToBottomNow,
    pinOnOpen,
    handleVirtualScroll,
    observeContentGrowth,
  }
}
