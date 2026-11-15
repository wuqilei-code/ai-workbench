<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import type { DiffView } from '@/utils/toolView'

const props = defineProps<{ view: DiffView; status?: string }>()
const emit = defineEmits<{ open: [path: string] }>()
const { t } = useI18n()
const msg = useMessage()

/** 默认只渲染前 160 行,长 diff 点开再看全部(避免一次挂载几千个节点) */
const PREVIEW_ROWS = 160
const showAll = ref(false)

const displayPath = computed(() => (props.view.path || '').replace(/^\/workspace\/?/i, ''))

const rows = computed(() =>
  showAll.value ? props.view.diff.rows : props.view.diff.rows.slice(0, PREVIEW_ROWS),
)

const restRows = computed(() => Math.max(0, props.view.diff.rows.length - PREVIEW_ROWS))

const modeLabel = computed(() => {
  if (props.view.mode === 'edit') return t('toolCall.mode.edit')
  if (props.view.mode === 'patch') return t('toolCall.mode.patch')
  return t('toolCall.mode.write')
})

/** 变更文本(仅增删行),便于贴到别处 */
const changeText = computed(() =>
  props.view.diff.rows
    .map((r) => (r.type === 'add' ? '+' + r.text : r.type === 'del' ? '-' + r.text : null))
    .filter((l): l is string => l !== null)
    .join('\n'),
)

async function copyChanges() {
  if (!changeText.value) return
  try {
    await navigator.clipboard.writeText(changeText.value)
    msg.success(t('toolCall.copied'))
  } catch (e: any) {
    msg.error(e?.message || t('toolCall.copyFailed'))
  }
}
</script>

<template>
  <div class="diff-card">
    <div class="diff-head">
      <svg class="d-ic" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
      <button
        v-if="view.path"
        type="button"
        class="diff-path diff-path--link"
        :title="`${view.path} · ${t('toolCall.openFile')}`"
        @click="emit('open', view.path)"
      >{{ displayPath }}</button>
      <span v-else class="diff-path">{{ t('toolCall.unnamedFile') }}</span>
      <span class="diff-mode">{{ modeLabel }}</span>
      <span v-if="view.replaceAll" class="diff-tag">{{ t('toolCall.replaceAll') }}</span>
      <span v-if="status === 'completed'" class="diff-applied">{{ t('toolCall.applied') }}</span>
      <span class="diff-stat">
        <span class="add">+{{ view.diff.additions }}</span>
        <span class="del">-{{ view.diff.deletions }}</span>
      </span>
      <button
        v-if="changeText"
        type="button"
        class="diff-act"
        :title="t('toolCall.copyChanges')"
        @click="copyChanges"
      >
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
      </button>
    </div>

    <div v-if="view.diff.rows.length" class="diff-body">
      <template v-for="(row, i) in rows" :key="i">
        <div v-if="row.type === 'gap'" class="dl gap">… {{ t('toolCall.diffGap', { n: row.count }) }}</div>
        <div v-else class="dl" :class="row.type">
          <span class="ln ln-old">{{ row.oldNo ?? '' }}</span>
          <span class="ln ln-new">{{ row.newNo ?? '' }}</span>
          <span class="sign">{{ row.type === 'add' ? '+' : row.type === 'del' ? '-' : ' ' }}</span>
          <span class="txt">{{ row.text }}</span>
        </div>
      </template>
      <button v-if="!showAll && restRows" type="button" class="diff-more" @click="showAll = true">
        {{ t('toolCall.showMoreLines', { n: restRows }) }}
      </button>
      <div v-else-if="view.diff.truncated" class="diff-trunc">
        {{ t('toolCall.diffTruncated', { n: view.diff.hiddenLines }) }}
      </div>
    </div>
    <div v-else class="diff-empty">{{ t('toolCall.empty') }}</div>
  </div>
</template>

<style scoped>
.diff-card {
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  overflow: hidden;
  background: var(--surface);
  min-width: 0;
}
.diff-head {
  display: flex; align-items: center; gap: 7px;
  padding: 7px 10px;
  border-bottom: 1px solid var(--border-soft);
  font-size: 11px; color: var(--text-2);
  min-width: 0;
}
.d-ic { color: var(--text-3); flex-shrink: 0; }
.diff-path {
  font-family: ui-monospace, monospace;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  min-width: 0;
}
.diff-path--link {
  padding: 0; border: 0; background: transparent;
  font-size: 11px; color: var(--text-2); text-align: left;
  cursor: pointer; border-radius: 4px;
}
.diff-path--link:hover { color: var(--accent-text); text-decoration: underline; }
.diff-path--link:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
.diff-mode {
  flex-shrink: 0; font-size: 10px; color: var(--text-3);
  padding: 0 5px; border: 1px solid var(--border-soft); border-radius: 4px;
}
.diff-tag {
  flex-shrink: 0; font-size: 10px; color: var(--warn);
  padding: 0 5px; border-radius: 4px;
  background: color-mix(in srgb, var(--warn) 12%, transparent);
}
.diff-applied {
  flex-shrink: 0; font-size: 10px; color: var(--ok);
  padding: 0 5px; border-radius: 4px;
  background: color-mix(in srgb, var(--ok) 12%, transparent);
}
.diff-act {
  flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center;
  padding: 2px; border: 0; background: transparent; border-radius: 4px;
  color: var(--text-3); cursor: pointer; line-height: 0;
}
.diff-act:hover { background: var(--accent-soft); color: var(--accent-text); }
.diff-stat {
  margin-left: auto; flex-shrink: 0;
  display: inline-flex; gap: 6px;
  font-family: ui-monospace, monospace; font-size: 11px;
}
.diff-stat .add { color: var(--ok); }
.diff-stat .del { color: var(--err); }

.diff-body {
  max-height: 320px; overflow: auto;
  scrollbar-width: thin;
  font-family: ui-monospace, monospace; font-size: 11.5px; line-height: 1.6;
}
.diff-body::-webkit-scrollbar { width: 6px; }
.diff-body::-webkit-scrollbar-thumb { background: transparent; border-radius: 3px; }
.diff-body:hover::-webkit-scrollbar-thumb { background: var(--border); }

.dl {
  display: flex; align-items: flex-start; gap: 0;
  padding: 0 8px 0 0;
  white-space: pre-wrap; word-break: break-word;
  color: var(--text-2);
}
.dl.add { background: color-mix(in srgb, var(--ok) 13%, transparent); color: var(--text); }
.dl.del { background: color-mix(in srgb, var(--err) 13%, transparent); color: var(--text); }
.dl.gap {
  padding: 2px 10px; color: var(--text-4); font-size: 10.5px;
  background: color-mix(in srgb, var(--text-4) 7%, transparent);
  border-top: 1px dashed var(--border-soft);
  border-bottom: 1px dashed var(--border-soft);
}
.ln {
  flex-shrink: 0; width: 3.2ch; text-align: right;
  padding-right: 6px; color: var(--text-4);
  user-select: none;
}
.ln-new { border-right: 1px solid var(--border-soft); margin-right: 6px; }
.sign {
  flex-shrink: 0; width: 1.2ch; user-select: none;
}
.dl.add .sign { color: var(--ok); }
.dl.del .sign { color: var(--err); }
.txt { flex: 1; min-width: 0; }

.diff-more {
  display: block; width: 100%;
  padding: 6px 10px; margin: 0;
  border: 0; border-top: 1px solid var(--border-soft);
  background: var(--surface-hover); color: var(--accent-text);
  font-size: 11px; cursor: pointer; text-align: center;
}
.diff-more:hover { background: var(--accent-soft); }
.diff-trunc, .diff-empty {
  padding: 6px 10px; font-size: 11px; color: var(--text-3);
}
</style>
