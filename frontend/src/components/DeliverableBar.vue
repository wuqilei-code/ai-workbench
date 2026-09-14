<script setup lang="ts">
/** 结果文件卡片区:展示 AI 本 turn 产出的交付文件,点击即下载。
 *  数据来源:AssistantTurn 通过 extractDeliverables(turn) 解析 AI 回复里的
 *  `[结果文件] <路径>` 标记得到 items,再交给本组件渲染。 */
defineProps<{ items: { path: string; name: string }[]; busy?: boolean; downloadingPath?: string | null }>()
defineEmits<{
  (e: 'preview', path: string): void
  (e: 'download', path: string): void
  (e: 'downloadAll'): void
}>()

/** 按扩展名给文件图标配色(与复制/刷新同款线框图标,用颜色区分类型) */
const FILE_COLORS: Record<string, string> = {
  pdf: '#E5484D', doc: '#3B82F6', docx: '#3B82F6', txt: '#6B7280', md: '#64748B',
  ppt: '#F59E0B', pptx: '#F59E0B',
  xls: '#10B981', xlsx: '#10B981', csv: '#10B981',
  png: '#EC4899', jpg: '#EC4899', jpeg: '#EC4899', gif: '#EC4899', svg: '#EC4899', webp: '#EC4899',
  zip: '#F59E0B', gz: '#F59E0B', tar: '#F59E0B', '7z': '#F59E0B', rar: '#F59E0B',
  json: '#64748B', xml: '#64748B', html: '#64748B',
}
const DEFAULT_COLOR = '#6B7280'

function fileColor(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase() || ''
  return FILE_COLORS[ext] || DEFAULT_COLOR
}
</script>

<template>
  <div v-if="items.length" class="deliverable-bar">
    <div class="deliverable-header">
      <span class="deliverable-title">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>
        结果文件
      </span>
      <button
        v-if="items.length > 1"
        class="download-all-btn"
        :disabled="busy"
        :title="busy ? '打包中…' : '打包下载本 turn 全部结果文件'"
        @click="$emit('downloadAll')"
      >
        <svg v-if="!busy" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        {{ busy ? '打包中…' : '打包下载' }}
      </button>
    </div>
    <div class="deliverable-list">
      <a
        v-for="item in items"
        :key="item.path"
        class="deliverable-item"
        :class="{ 'item-busy': downloadingPath === item.path }"
        :title="`预览 ${item.path}`"
        @click="$emit('preview', item.path)"
      >
        <span class="deliverable-icon">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" :stroke="fileColor(item.name)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
        </span>
        <span class="deliverable-name">{{ item.name }}</span>
        <span
          class="deliverable-action"
          :class="{ spin: downloadingPath === item.path }"
          title="下载"
          @click.stop="$emit('download', item.path)"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        </span>
      </a>
    </div>
  </div>
</template>

<style scoped>
.deliverable-bar {
  margin-top: 10px;
  max-width: 80%;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
  overflow: hidden;
}
.deliverable-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 12px;
  background: var(--fill-0);
  border-bottom: 1px solid var(--border-soft);
  color: var(--text-2);
  font-size: 13px;
  font-weight: 600;
}
.deliverable-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.download-all-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: transparent;
  color: var(--text-2);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background .12s, border-color .12s, color .12s;
}
.download-all-btn:hover {
  background: var(--fill-1);
  border-color: var(--fill-2);
  color: var(--text);
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
  border-color: var(--text-4);
}
.deliverable-icon { display: inline-flex; flex-shrink: 0; }
.deliverable-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.deliverable-action {
  display: inline-flex;
  align-items: center;
  color: var(--text-3);
  flex-shrink: 0;
  transition: color .12s;
}
.deliverable-item:hover .deliverable-action { color: var(--accent); }
.item-busy .deliverable-action { opacity: .6; }
.deliverable-item.item-busy .deliverable-action.spin { animation: dl-pulse 1s ease-in-out infinite; }
@keyframes dl-pulse { 0%,100% { opacity: .5; } 50% { opacity: 1; } }
</style>
