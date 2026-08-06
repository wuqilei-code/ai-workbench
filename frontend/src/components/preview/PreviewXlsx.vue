<script setup lang="ts">
import { ref, watch } from 'vue'
import * as XLSX from 'xlsx'

const props = defineProps<{ blob: Blob }>()
const sheets = ref<{ name: string; html: string; truncated: boolean }[]>([])
const error = ref<string | null>(null)

const MAX_ROWS = 1000
const MAX_COLS = 50

async function parse(blob: Blob) {
  error.value = null
  try {
    const buf = await blob.arrayBuffer()
    const wb = XLSX.read(buf, { type: 'array' })
    sheets.value = wb.SheetNames.map((name) => {
      const ws = wb.Sheets[name]
      const { html, truncated } = generateTableHtml(ws)
      return { name, html, truncated }
    })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '表格解析失败'
  }
}

function generateTableHtml(ws: XLSX.WorkSheet): { html: string; truncated: boolean } {
  const ref = ws['!ref']
  if (!ref) return { html: '<div class="xlsx-empty">空工作表</div>', truncated: false }

  const range = XLSX.utils.decode_range(ref)
  const originalRows = range.e.r - range.s.r + 1
  const originalCols = range.e.c - range.s.c + 1
  let truncated = false

  // 限制行数
  if (originalRows > MAX_ROWS) {
    range.e.r = range.s.r + MAX_ROWS - 1
    truncated = true
  }
  // 限制列数
  if (originalCols > MAX_COLS) {
    range.e.c = range.s.c + MAX_COLS - 1
    truncated = true
  }

  // 按行遍历单元格,构建二维数组
  const rows: (string | number)[][] = []
  for (let r = range.s.r; r <= range.e.r; r++) {
    const row: (string | number)[] = []
    for (let c = range.s.c; c <= range.e.c; c++) {
      const cellAddr = XLSX.utils.encode_cell({ r, c })
      const cell = ws[cellAddr]
      if (cell) {
        // 处理不同类型的单元格
        if (cell.t === 's') {
          // 字符串类型
          row.push(cell.v || '')
        } else if (cell.t === 'n') {
          // 数字类型
          row.push(cell.v ?? 0)
        } else if (cell.t === 'b') {
          // 布尔类型
          row.push(cell.v ? '是' : '否')
        } else if (cell.t === 'd') {
          // 日期类型
          row.push(formatDate(cell.v))
        } else if (cell.t === 'z') {
          // 公式类型
          row.push(cell.v || '')
        } else {
          row.push(cell.w || cell.v || '')
        }
      } else {
        row.push('')
      }
    }
    rows.push(row)
  }

  // 构建 HTML
  if (rows.length === 0) {
    return { html: '<div class="xlsx-empty">空工作表</div>', truncated }
  }

  const headerRow = rows[0]
  const dataRows = rows.slice(1)

  let html = '<table class="xlsx-table">'
  html += '<thead><tr>'
  for (const cell of headerRow) {
    html += `<th>${escapeHtml(cell)}</th>`
  }
  html += '</tr></thead>'

  html += '<tbody>'
  for (const row of dataRows) {
    html += '<tr>'
    for (const cell of row) {
      html += `<td>${escapeHtml(cell)}</td>`
    }
    html += '</tr>'
  }
  html += '</tbody>'
  html += '</table>'

  return { html, truncated }
}

function escapeHtml(val: string | number): string {
  const str = String(val)
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;')
}

function formatDate(dateVal: any): string {
  if (!dateVal) return ''
  try {
    // 处理 Excel 日期序列号
    if (typeof dateVal === 'number') {
      const date = new Date(Math.round((dateVal - 25569) * 86400 * 1000))
      if (!isNaN(date.getTime())) {
        return date.toLocaleString('zh-CN', {
          year: 'numeric',
          month: '2-digit',
          day: '2-digit',
          hour: '2-digit',
          minute: '2-digit'
        })
      }
    }
    return String(dateVal)
  } catch {
    return String(dateVal)
  }
}

watch(() => props.blob, (b) => { if (b) parse(b) }, { immediate: true })
</script>

<template>
  <div class="xlsx-wrap">
    <div v-if="error" class="xlsx-error">表格解析失败:{{ error }}</div>
    <template v-else>
      <n-tabs v-if="sheets.length > 1" type="line" size="small" animated>
        <n-tab-pane v-for="s in sheets" :key="s.name" :name="s.name" :tab="s.name">
          <div v-if="s.truncated" class="xlsx-truncate">
            仅显示前 {{ MAX_ROWS }} 行或 {{ MAX_COLS }} 列,完整数据请下载
          </div>
          <div class="sheet-html" v-html="s.html" />
        </n-tab-pane>
      </n-tabs>
      <div v-else-if="sheets.length === 1">
        <div v-if="sheets[0].truncated" class="xlsx-truncate">
          仅显示前 {{ MAX_ROWS }} 行或 {{ MAX_COLS }} 列,完整数据请下载
        </div>
        <div class="sheet-html" v-html="sheets[0].html" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.xlsx-wrap {
  overflow: auto;
  height: 100%;
  background: #ffffff !important;
  color: #333333 !important;
}
.xlsx-truncate { padding: 8px 12px; background: #fff3cd; color: #856404; font-size: 12px; margin: 4px; }
.xlsx-error { color: #ff6b6b; padding: 16px; text-align: center; }
.xlsx-empty { padding: 32px; text-align: center; color: #999; }

/* 强制覆盖暗色主题下的文字颜色继承 */
.xlsx-wrap :deep(.sheet-html),
.xlsx-wrap :deep(.sheet-html *),
.xlsx-wrap :deep(.xlsx-table),
.xlsx-wrap :deep(.xlsx-table *),
.xlsx-wrap :deep(.xlsx-table td),
.xlsx-wrap :deep(.xlsx-table th),
.xlsx-wrap :deep(.xlsx-table td *),
.xlsx-wrap :deep(.xlsx-table th *) {
  color: #333333 !important;
  background-color: transparent;
}

.xlsx-wrap :deep(.sheet-html) {
  padding: 8px;
  overflow: auto;
  background: #ffffff;
}

.xlsx-wrap :deep(.xlsx-table) {
  border-collapse: collapse;
  font-size: 12px;
  width: max-content;
  min-width: 100%;
  background: #ffffff;
}

.xlsx-wrap :deep(.xlsx-table td),
.xlsx-wrap :deep(.xlsx-table th) {
  border: 1px solid #dddddd;
  padding: 3px 8px;
  white-space: nowrap;
  text-align: left;
  color: #333333 !important;
}

.xlsx-wrap :deep(.xlsx-table th) {
  background: #f5f5f5 !important;
  font-weight: 600;
}

.xlsx-wrap :deep(.xlsx-table tbody tr:hover) {
  background: #f0f7ff !important;
}

/* n-tabs 在白色背景下的样式修复 */
.xlsx-wrap :deep(.n-tabs-tab-pane) {
  background: #ffffff;
  color: #333333 !important;
}

/* tabs 标签文字颜色强制修复 */
.xlsx-wrap :deep(.n-tabs-nav-item),
.xlsx-wrap :deep(.n-tabs-nav-item *),
.xlsx-wrap :deep(.n-tabs-tab),
.xlsx-wrap :deep(.n-tabs-tab *) {
  color: #333333 !important;
}

/* 活动 tab 标签样式 */
.xlsx-wrap :deep(.n-tabs-nav-item--active),
.xlsx-wrap :deep(.n-tabs-nav-item--active *) {
  color: #18a058 !important;
}
</style>
