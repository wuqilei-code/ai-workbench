/** 文件预览类型分发 */

export type PreviewKind =
  | 'image' | 'pdf' | 'markdown' | 'code' | 'text'
  | 'docx' | 'xlsx' | 'audio' | 'video' | 'unsupported'

const EXT_MAP: Record<string, PreviewKind> = {
  // 图片
  png: 'image', jpg: 'image', jpeg: 'image', gif: 'image',
  webp: 'image', svg: 'image', bmp: 'image', ico: 'image',
  // PDF
  pdf: 'pdf',
  // Markdown
  md: 'markdown', markdown: 'markdown',
  // Office
  docx: 'docx', doc: 'docx',
  xlsx: 'xlsx', xls: 'xlsx', csv: 'xlsx',
  pptx: 'unsupported', ppt: 'unsupported',
  // 音视频
  mp3: 'audio', wav: 'audio', ogg: 'audio', flac: 'audio', m4a: 'audio',
  mp4: 'video', webm: 'video', mov: 'video', avi: 'video', mkv: 'video',
  // 代码
  js: 'code', mjs: 'code', cjs: 'code', ts: 'code', jsx: 'code', tsx: 'code',
  vue: 'code', py: 'code', java: 'code', kt: 'code', go: 'code', rs: 'code',
  c: 'code', h: 'code', cpp: 'code', cc: 'code', cs: 'code',
  rb: 'code', php: 'code', swift: 'code', scala: 'code',
  html: 'code', htm: 'code', css: 'code', scss: 'code', sass: 'code', less: 'code',
  json: 'code', json5: 'code', yml: 'code', yaml: 'code', toml: 'code', ini: 'code',
  sh: 'code', bash: 'code', zsh: 'code', ps1: 'code', bat: 'code',
  sql: 'code', xml: 'code', graphql: 'code', dockerfile: 'code',
  // 文本
  txt: 'text', log: 'text', env: 'text', conf: 'text', properties: 'text', md5: 'text',
}

/** 路径 → 预览类型 */
export function extToKind(path: string): PreviewKind {
  const ext = path.split('.').pop()?.toLowerCase() || ''
  return EXT_MAP[ext] || 'unsupported'
}

/** 路径 → highlight.js 语言名 */
export function extToLang(path: string): string | undefined {
  const ext = path.split('.').pop()?.toLowerCase() || ''
  const map: Record<string, string> = {
    js: 'javascript', mjs: 'javascript', cjs: 'javascript',
    ts: 'typescript', jsx: 'javascript', tsx: 'typescript',
    vue: 'xml', py: 'python', java: 'java', kt: 'kotlin',
    go: 'go', rs: 'rust', c: 'c', h: 'c', cpp: 'cpp', cc: 'cpp',
    cs: 'csharp', rb: 'ruby', php: 'php', swift: 'swift', scala: 'scala',
    html: 'xml', htm: 'xml', css: 'css', scss: 'scss', sass: 'sass', less: 'less',
    json: 'json', yml: 'yaml', yaml: 'yaml', toml: 'ini', ini: 'ini',
    sh: 'bash', bash: 'bash', zsh: 'bash', ps1: 'powershell', bat: 'dos',
    sql: 'sql', xml: 'xml', graphql: 'graphql',
  }
  return map[ext]
}

/** 可编辑的预览类型(提供预览/编辑切换) */
export const EDITABLE_KINDS: Set<PreviewKind> = new Set(['markdown', 'code', 'text'])

/** 二进制类型(需通过 fetchBlob 获取,不能用 content API) */
export const BINARY_KINDS: Set<PreviewKind> = new Set([
  'image', 'pdf', 'docx', 'xlsx', 'audio', 'video',
])

export function fmtSize(n: number): string {
  if (!n) return '-'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}
