<script setup lang="ts">
/** 结果文件卡片区:展示 AI 本 turn 产出的交付文件,点击即下载。
 *  数据来源:AssistantTurn 通过 extractDeliverables(turn) 解析 AI 回复里的
 *  `[结果文件] <路径>` 标记得到 items,再交给本组件渲染。 */
defineProps<{ items: { path: string; name: string }[]; busy?: boolean }>()
defineEmits<{
  (e: 'preview', path: string): void
  (e: 'download', path: string): void
  (e: 'downloadAll'): void
}>()

/** 按扩展名给图标的简易映射(覆盖常见文档/数据/图片/压缩格式) */
const ICONS: Record<string, string> = {
  pdf: '📄', doc: '📄', docx: '📄', txt: '📄', md: '📄',
  ppt: '📊', pptx: '📊',
  xls: '📈', xlsx: '📈', csv: '📈',
  png: '🖼️', jpg: '🖼️', jpeg: '🖼️', gif: '🖼️', svg: '🖼️', webp: '🖼️',
  zip: '🗜️', gz: '🗜️', tar: '🗜️', '7z': '🗜️', rar: '🗜️',
  json: '🗂️', xml: '🗂️', html: '🗂️',
}
const DEFAULT_ICON = '📦'

function icon(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase() || ''
  return ICONS[ext] || DEFAULT_ICON
}
</script>

<template>
  <div v-if="items.length" class="deliverable-bar">
    <div class="deliverable-header">
      <span>📦 结果文件</span>
      <button
        v-if="items.length > 1"
        class="download-all-btn"
        :disabled="busy"
        :title="busy ? '打包中…' : '打包下载本 turn 全部结果文件'"
        @click="$emit('downloadAll')"
      >
        {{ busy ? '打包中…' : '⬇ 打包下载' }}
      </button>
    </div>
    <div class="deliverable-list">
      <a
        v-for="item in items"
        :key="item.path"
        class="deliverable-item"
        :title="`预览 ${item.path}`"
        @click="$emit('preview', item.path)"
      >
        <span class="deliverable-icon">{{ icon(item.name) }}</span>
        <span class="deliverable-name">{{ item.name }}</span>
        <span
          class="deliverable-action"
          title="下载"
          @click.stop="$emit('download', item.path)"
        >📥</span>
      </a>
    </div>
  </div>
</template>

<style scoped>
.deliverable-bar {
  margin-top: 10px;
  border: 1px solid rgba(88, 196, 155, 0.3);
  border-radius: var(--radius-sm);
  background: rgba(88, 196, 155, 0.05);
  overflow: hidden;
}
.deliverable-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 12px;
  background: rgba(88, 196, 155, 0.1);
  color: var(--ok);
  font-size: 13px;
  font-weight: 600;
}
.download-all-btn {
  padding: 2px 10px;
  border: 1px solid rgba(88, 196, 155, 0.4);
  border-radius: 6px;
  background: transparent;
  color: var(--ok);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background .12s, border-color .12s;
}
.download-all-btn:hover {
  background: rgba(88, 196, 155, 0.12);
  border-color: rgba(88, 196, 155, 0.6);
}
.download-all-btn:disabled {
  opacity: .5;
  cursor: not-allowed;
}
.deliverable-list {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 8px;
}
.deliverable-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 280px;
  flex-shrink: 0;
  padding: 5px 10px;
  border-radius: var(--radius-sm);
  background: var(--bg-elev);
  color: var(--text);
  cursor: pointer;
  text-decoration: none;
  font-size: 13px;
  border: 1px solid var(--border);
  transition: background .12s, border-color .12s;
}
.deliverable-item:hover {
  background: var(--surface-hover);
  border-color: rgba(88, 196, 155, 0.5);
}
.deliverable-icon { font-size: 16px; flex-shrink: 0; }
.deliverable-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.deliverable-action { font-size: 14px; opacity: .8; flex-shrink: 0; }
</style>
