<script setup lang="ts">
import { ref, watch } from 'vue'
import JSZip from 'jszip'
import ExcelJS from 'exceljs'
import xlsxPreview from 'xlsx-preview'
import * as XLSX from 'xlsx'

const props = defineProps<{ blob: Blob; path: string }>()
const sheets = ref<{ name: string; html: string }[]>([])
const error = ref<string | null>(null)
const parsing = ref(false)

const MAX_ROWS = 1000
const MAX_COLS = 50

function isLegacy(): boolean {
  const ext = (props.path || '').toLowerCase()
  return ext.endsWith('.xls') || ext.endsWith('.csv')
}

async function parse(blob: Blob) {
  error.value = null
  parsing.value = true
  try {
    if (isLegacy()) {
      sheets.value = await parseWithSheetjs(blob)
    } else {
      try {
        sheets.value = await parseWithXlsxPreview(blob)
      } catch (e) {
        // xlsx-preview 解析失败时回退到 SheetJS 基础渲染
        sheets.value = await parseWithSheetjs(blob)
      }
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '表格解析失败'
  } finally {
    parsing.value = false
  }
}

/* ------------------------- xlsx-preview 路径(xlsx) ------------------------- */

async function parseWithXlsxPreview(blob: Blob): Promise<{ name: string; html: string }[]> {
  const names = await readSheetNames(blob)
  const data = await ensureSheetViews(await blob.arrayBuffer())
  const htmls = (await xlsxPreview.xlsx2Html(data, {
    output: 'string',
    separateSheets: true,
    minimumRows: 1,
    minimumCols: 1
  })) as string[]

  return names.map((name, i) => ({ name, html: unwrapBody(htmls[i] ?? '') }))
}

async function readSheetNames(blob: Blob): Promise<string[]> {
  const wb = new ExcelJS.Workbook()
  await wb.xlsx.load(await blob.arrayBuffer())
  return wb.worksheets.map((w) => w.name)
}

function unwrapBody(html: string): string {
  return html.replace(/<body[^>]*>/i, '').replace(/<\/body>/gi, '')
}

// xlsx-preview 依赖工作表的 <sheetViews>;缺失时(部分工具/生成器)会导致内部崩溃,
// 这里为每个工作表注入 <sheetView>,保证可渲染。
async function ensureSheetViews(ab: ArrayBuffer): Promise<ArrayBuffer> {
  const zip = await JSZip.loadAsync(ab)
  const sheetPaths = Object.keys(zip.files).filter((p) =>
    /^xl\/worksheets\/sheet\d+\.xml$/i.test(p)
  )

  for (const sp of sheetPaths) {
    let xml = await zip.file(sp)!.async('string')
    if (!/<sheetViews>/i.test(xml)) {
      xml = xml.replace(
        /<worksheet[^>]*>/i,
        (m) => m + '<sheetViews><sheetView workbookViewId="0"/></sheetViews>'
      )
      zip.file(sp, xml)
    }
  }

  return zip.generateAsync({ type: 'arraybuffer', compression: 'DEFLATE' })
}

/* ------------------------- SheetJS 回退路径(xls/csv) ------------------------ */

async function parseWithSheetjs(blob: Blob): Promise<{ name: string; html: string }[]> {
  const buf = await blob.arrayBuffer()
  const wb = XLSX.read(buf, { type: 'array' })
  return wb.SheetNames.map((name) => {
    const ws = wb.Sheets[name]
    return { name, html: generateTableHtml(ws) }
  })
}

function generateTableHtml(ws: XLSX.WorkSheet): string {
  const ref = ws['!ref']
  if (!ref) return '<div class="xlsx-empty">空工作表</div>'

  const range = XLSX.utils.decode_range(ref)
  const originalRows = range.e.r - range.s.r + 1
  const originalCols = range.e.c - range.s.c + 1
  let truncated = false

  if (originalRows > MAX_ROWS) {
    range.e.r = range.s.r + MAX_ROWS - 1
    truncated = true
  }
  if (originalCols > MAX_COLS) {
    range.e.c = range.s.c + MAX_COLS - 1
    truncated = true
  }

  const rows: (string | number)[][] = []
  for (let r = range.s.r; r <= range.e.r; r++) {
    const row: (string | number)[] = []
    for (let c = range.s.c; c <= range.e.c; c++) {
      const cellAddr = XLSX.utils.encode_cell({ r, c })
      const cell = ws[cellAddr]
      if (cell) {
        if (cell.t === 's') {
          row.push(cell.v || '')
        } else if (cell.t === 'n') {
          row.push(cell.v ?? 0)
        } else if (cell.t === 'b') {
          row.push(cell.v ? '是' : '否')
        } else if (cell.t === 'd') {
          row.push(formatDate(cell.v))
        } else {
          row.push(cell.w || cell.v || '')
        }
      } else {
        row.push('')
      }
    }
    if (row.some((v) => v !== '')) rows.push(row)
  }

  if (rows.length === 0) return '<div class="xlsx-empty">空工作表</div>'

  const headerRow = rows[0]
  const dataRows = rows.slice(1)

  let html = `<table class="xlsx-table">`
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

  if (truncated) {
    html =
      `<div class="xlsx-truncate">仅显示前 ${MAX_ROWS} 行或 ${MAX_COLS} 列,完整数据请下载</div>` +
      html
  }

  return html
}

function escapeHtml(val: string | number): string {
  return String(val)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;')
}

function formatDate(dateVal: any): string {
  if (!dateVal) return ''
  try {
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
    <div v-if="parsing" class="xlsx-parsing">
      <span class="xlsx-spinner"></span>
      <span>正在解析表格…</span>
    </div>
    <template v-else>
      <div v-if="error" class="xlsx-error">表格解析失败:{{ error }}</div>
      <template v-else>
        <n-tabs v-if="sheets.length > 1" type="line" size="small" animated>
          <n-tab-pane v-for="s in sheets" :key="s.name" :name="s.name" :tab="s.name">
            <div class="sheet-html" v-html="s.html" />
          </n-tab-pane>
        </n-tabs>
        <div v-else-if="sheets.length === 1" class="sheet-html" v-html="sheets[0].html" />
      </template>
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
.xlsx-parsing {
  display: flex; align-items: center; justify-content: center; gap: 10px;
  height: 100%; color: #888; font-size: 13px;
}
.xlsx-spinner {
  width: 16px; height: 16px; border: 2px solid #ccc; border-top-color: #555;
  border-radius: 50%; animation: xlsx-spin 0.8s linear infinite;
}
@keyframes xlsx-spin { to { transform: rotate(360deg); } }
.xlsx-empty { padding: 32px; text-align: center; color: #999; }

/* 两种渲染路径共用:白底 + 覆盖暗色主题文字继承 */
.sheet-html {
  padding: 8px;
  overflow: auto;
  background: #ffffff;
}
.sheet-html :deep(*) {
  color: #333333 !important;
  background-color: transparent;
}
.sheet-html :deep(table) {
  border-collapse: collapse;
}
.sheet-html :deep(td),
.sheet-html :deep(th) {
  padding: 3px 8px;
  white-space: nowrap;
  text-align: left;
}
</style>
