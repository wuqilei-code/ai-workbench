<script setup lang="ts">
import { ref, watch, nextTick, onMounted, onBeforeUnmount, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import ChatMessage from '@/components/ChatMessage.vue'
import AssistantTurn from '@/components/AssistantTurn.vue'
import ChatInput from '@/components/ChatInput.vue'
import ModelSelector from '@/components/ModelSelector.vue'
import FileTree from '@/components/FileTree.vue'

const route = useRoute()
const chat = useChatStore()
const scrollRef = ref<HTMLDivElement | null>(null)
const showFiles = ref(false)

const sessionId = computed(() => {
  const id = route.params.id
  return id ? Number(id) : null
})

async function ensureSession() {
  if (sessionId.value && sessionId.value !== chat.currentSessionId) {
    await chat.selectSession(sessionId.value)
  }
}

onMounted(ensureSession)
watch(sessionId, ensureSession)

function scrollToBottom() {
  nextTick(() => {
    const el = scrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}
watch(() => chat.messages.length, scrollToBottom)
watch(() => chat.turns.length, scrollToBottom)
watch(() => chat.streamingText, scrollToBottom)

onBeforeUnmount(() => {
  chat.closeStream()
})

/** 当前流式目标消息是否已落入某个 turn(step/finalAnswer) */
const activeTurnExists = computed(() => {
  const id = chat.streamingMessageId
  if (!id) return false
  return chat.turns.some((t) =>
    t.steps.some((s) => s.message.opencodeMessageId === id) ||
    (t.finalAnswer?.message.opencodeMessageId === id),
  )
})

/** 最后一个 turn 是否已有 assistant 内容(避免与"思考中"兜底重复) */
const lastTurnHasContent = computed(() => {
  const list = chat.turns
  if (list.length === 0) return false
  const last = list[list.length - 1]
  return last.steps.length > 0 || !!last.finalAnswer
})

const showStreamingBubble = computed(
  () => chat.isStreaming && !!chat.streamingText && !activeTurnExists.value,
)
const showThinkingFallback = computed(
  () =>
    chat.isStreaming &&
    !chat.streamingText &&
    !activeTurnExists.value &&
    !lastTurnHasContent.value,
)
</script>

<template>
  <div class="chat-view">
    <div class="chat-head">
      <div class="head-left">
        <span class="title">{{ chat.currentSession?.title || 'AI Workbench' }}</span>
        <n-tag :type="chat.connected ? 'success' : 'default'" size="tiny" round>
          {{ chat.connected ? '已连接' : '未连接' }}
        </n-tag>
      </div>
      <div class="head-right">
        <ModelSelector />
        <n-button size="small" quaternary @click="showFiles = !showFiles">工作区</n-button>
      </div>
    </div>

    <div class="chat-body" ref="scrollRef">
      <template v-if="chat.currentSessionId">
        <template v-for="t in chat.turns" :key="t.id">
          <ChatMessage v-if="t.userMessage" :message="t.userMessage" />
          <AssistantTurn v-if="t.finalAnswer || t.steps.length" :turn="t" :session-id="chat.currentSessionId" />
        </template>
        <!-- 流式兜底:token 已到但 meta 尚未并入 turn -->
        <ChatMessage
          v-if="showStreamingBubble"
          :message="{ id: -1, role: 'assistant', content: chat.streamingText, status: 'streaming' }"
          :streaming="true"
        />
        <div v-if="showThinkingFallback" class="thinking">
          <n-spin size="small" /> AI 思考中…
        </div>
      </template>
      <n-empty v-else description="选择左侧会话或点击「新会话」开始" style="margin-top: 80px" />
    </div>

    <ChatInput v-if="chat.currentSessionId" />

    <n-drawer v-model:show="showFiles" :width="440" placement="right">
      <n-drawer-content title="工作区" closable>
        <n-tabs type="line" animated size="small">
          <n-tab-pane name="files" tab="文件">
            <FileTree />
          </n-tab-pane>
          <n-tab-pane name="skills" tab="技能">
            <FileTree root=".opencode/skills" skills-mode />
          </n-tab-pane>
        </n-tabs>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<style scoped>
.chat-view { display: flex; flex-direction: column; height: 100vh; }
.chat-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 16px; border-bottom: 1px solid #2a2a2e; background: #1a1a1e;
}
.head-left { display: flex; align-items: center; gap: 10px; }
.head-left .title { font-weight: 600; }
.head-right { display: flex; align-items: center; gap: 8px; }
.chat-body { flex: 1; overflow-y: auto; padding: 20px; }
.thinking { display: flex; align-items: center; gap: 8px; color: #888; padding: 8px 0; }
</style>
