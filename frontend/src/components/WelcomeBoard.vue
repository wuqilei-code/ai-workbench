<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { chatApi } from '@/api/chat'
import type { TemplateVo } from '@/types'

/** 空会话主页(Work with AI):
 *  - 顶部一排「分类标签」,选中某分类后展示该分类下最多 3 条模板;
 *  - 排序:置顶的排前面(按置顶顺序),其余按默认顺序;
 *  - 置顶在「模板库 → 我的」里设置。 */
const emit = defineEmits<{
  (e: 'select', prompt: string): void
  (e: 'use-html', payload: { title: string; content: string }): void
}>()

const mine = ref<TemplateVo[]>([])
const { t: t2 } = useI18n()

const CATEGORY_META: Record<string, { icon: string }> = {
  数据分析: { icon: '📊' },
  内容创作: { icon: '✍️' },
  办公: { icon: '📄' },
  开发: { icon: '⌨️' },
  其他: { icon: '✨' },
}
const CATEGORY_ORDER = ['数据分析', '内容创作', '办公', '开发', '其他']
const PER_CATEGORY = 3

/** 出现过的分类(按偏好顺序) */
const cats = computed(() => {
  const set = new Set<string>()
  for (const t of mine.value) if (t.category) set.add(t.category)
  return Array.from(set).sort((a, b) => {
    const ia = CATEGORY_ORDER.indexOf(a)
    const ib = CATEGORY_ORDER.indexOf(b)
    return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib)
  })
})

const activeCat = ref('')
// 分类列表变化时,保证有选中项
watch(cats, (list) => {
  if (!list.includes(activeCat.value)) activeCat.value = list[0] || ''
})

/** 当前分类下最多 3 条:置顶优先(按置顶顺序),其余保持默认顺序 */
const suggestions = computed(() => {
  const list = mine.value.filter((t) => t.category === activeCat.value)
  return [...list]
    .sort((a, b) => {
      const pa = a.pinned ? 1 : 0
      const pb = b.pinned ? 1 : 0
      if (pa !== pb) return pb - pa
      if (pa && pb) return (a.pinOrder ?? 0) - (b.pinOrder ?? 0)
      return 0
    })
    .slice(0, PER_CATEGORY)
})

const isHtml = (t: TemplateVo) => (t.format || 'prompt') === 'html'

onMounted(async () => {
  try {
    const list = await chatApi.listTemplates()
    mine.value = list.filter((t) => !t.builtin)
    if (!activeCat.value) activeCat.value = cats.value[0] || ''
  } catch {
    mine.value = []
  }
})

function onSuggestion(t: TemplateVo) {
  if (isHtml(t)) emit('use-html', { title: t.title, content: t.content || '' })
  else emit('select', t.content || '')
}
</script>

<template>
  <div class="home">
    <div class="home-head">
      <span class="home-logo">
        <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
      </span>
    <h1 class="home-title">{{ t2('welcome.title') }}</h1>
    </div>

    <!-- 输入框由父级 ChatInput 渲染在这里的下方,通过插槽占位 -->
    <slot />

    <!-- 分类标签 -->
    <div v-if="cats.length" class="home-chips">
      <button
        v-for="c in cats"
        :key="c"
        class="chip"
        :class="{ on: activeCat === c }"
        @click="activeCat = c"
      >
        <span class="chip-ico">{{ CATEGORY_META[c]?.icon || '✨' }}</span>{{ c }}
      </button>
    </div>

    <!-- 当前分类最多 3 条(置顶优先);容器固定 3 行高度,切换标签时输入框位置不动 -->
    <div v-if="cats.length" class="home-sugs">
      <button v-for="t in suggestions" :key="t.id" class="sug" @click="onSuggestion(t)">
        <span v-if="t.pinned" class="sug-pin" :title="t2('welcome2.pinned')">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M14 2l8 8-4 1-3 3-1 5-2-2-4 4-1-1 4-4-2-2 5-1 3-3z"/></svg>
        </span>
        <span class="sug-text">{{ t.description || t.title }}</span>
        <span class="sug-arrow">↑</span>
      </button>
    <div v-if="!suggestions.length" class="home-empty">{{ t2('welcome.empty') }}</div>
    </div>
  </div>
</template>

<style scoped>
.home {
  max-width: 900px; margin: 0 auto; width: 100%;
  min-height: 100%;
  padding: 0 24px;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
/* 主页输入框(插槽)占满首页宽度,与下方建议列表等宽 */
.home :deep(.chat-input) { width: 100%; align-self: stretch; padding-left: 0; padding-right: 0; }
.home :deep(.input-card) { width: 100%; align-self: stretch; }
.home-head { display: flex; align-items: center; gap: 12px; margin-bottom: 30px; }
.home-logo { color: var(--text); display: inline-flex; }
.home-title { font-size: 30px; font-weight: 800; letter-spacing: -.8px; color: var(--text); margin: 0; }

.home-chips { display: flex; flex-wrap: wrap; gap: 10px; justify-content: center; width: 100%; margin-top: 22px; }
.chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 16px; border-radius: 12px; cursor: pointer;
  font-size: 13.5px; color: var(--text-2);
  background: var(--surface); border: 1px solid var(--border-soft);
  font-family: inherit; transition: border-color .15s, color .15s, background .15s;
}
.chip:hover { border-color: var(--border); color: var(--text); }
.chip.on { border-color: var(--accent); color: var(--accent-text); background: var(--accent-soft); }
.chip-ico { font-size: 15px; }

.home-sugs { width: 100%; margin-top: 20px; display: flex; flex-direction: column; min-height: 150px; }
.sug {
  display: flex; align-items: center; gap: 10px;
  padding: 13px 4px; min-height: 50px; box-sizing: border-box;
  border: none; border-bottom: 1px solid var(--border-soft);
  background: transparent; cursor: pointer; text-align: left; width: 100%;
  font-family: inherit; font-size: 13.5px; color: var(--text-2);
  transition: color .15s;
}
.sug:first-child { border-top: 1px solid var(--border-soft); }
.sug:hover { color: var(--text); }
.sug-pin { flex-shrink: 0; display: inline-flex; color: var(--accent-text); }
.sug-text { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sug-arrow { flex-shrink: 0; color: var(--text-4); font-size: 15px; transition: color .15s, transform .15s; }
.sug:hover .sug-arrow { color: var(--accent-text); transform: translateY(-2px); }
.home-empty { padding: 14px 4px; font-size: 13px; color: var(--text-4); }

@media (max-width: 720px) {
  .home-title { font-size: 24px; }
}
</style>
