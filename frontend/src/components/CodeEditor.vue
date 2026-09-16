<script setup lang="ts">
import { ref, shallowRef, watch, onBeforeUnmount } from 'vue'
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { syntaxHighlighting, defaultHighlightStyle, indentOnInput, bracketMatching } from '@codemirror/language'
import { oneDark } from '@codemirror/theme-one-dark'
import { python } from '@codemirror/lang-python'
import { javascript } from '@codemirror/lang-javascript'
import { json } from '@codemirror/lang-json'
import { markdown } from '@codemirror/lang-markdown'
import { yaml } from '@codemirror/lang-yaml'
import { css } from '@codemirror/lang-css'
import { html } from '@codemirror/lang-html'
import { xml } from '@codemirror/lang-xml'
import { sql } from '@codemirror/lang-sql'

const props = withDefaults(defineProps<{
  modelValue: string
  /** 语言标识,留空按文件扩展名推断;已知扩展名直接传入 key(兼容 vue-tsc 简易推断) */
  language?: string
  /** 按文件名解析语言标识(如 app.py → python) */
  filename?: string
  readOnly?: boolean
  isDark?: boolean
}>(), {
  language: '',
  filename: '',
  readOnly: false,
  isDark: false,
})

const emit = defineEmits<{ (e: 'update:modelValue', v: string): void; (e: 'blur'): void }>()

const host = ref<HTMLElement | null>(null)
const view = shallowRef<EditorView | null>(null)
// 记录当前 pending 的 doc,以便在 view 构建后同步;避免 props.modelValue 与 view.state 滚雪球
let lastValue = props.modelValue || ''

/** 简单文件扩展名 → 语言包映射(CodeMirror 语言选择按需) */
function detectLanguage(filename: string): string {
  const ext = (filename.split('.').pop() || '').toLowerCase()
  const map: Record<string, string> = {
    py: 'python', pyi: 'python',
    js: 'javascript', mjs: 'javascript', cjs: 'javascript', jsx: 'javascript', ts: 'javascript', tsx: 'javascript',
    json: 'json', jsonc: 'json',
    md: 'markdown', markdown: 'markdown',
    yml: 'yaml', yaml: 'yaml',
    css: 'css', scss: 'scss',
    html: 'html', htm: 'html', vue: 'html',
    xml: 'xml', svg: 'xml',
    sql: 'sql',
    sh: 'shell', bash: 'shell', zsh: 'shell',
    txt: 'text', text: 'text',
  }
  return map[ext] || ''
}

function langExtension(lang: string) {
  switch (lang) {
    case 'python': return python()
    case 'javascript': return javascript({ jsx: true })
    case 'json': return json()
    case 'markdown': return markdown()
    case 'yaml': return yaml()
    case 'css': return css()
    case 'html': return html()
    case 'xml': return xml()
    case 'sql': return sql()
    default: return []
  }
}

function darkTheme(isDark: boolean) {
  return isDark ? oneDark : []
}

function build() {
  if (!host.value) return
  const lang = props.language || detectLanguage(props.filename || '')
  const state = EditorState.create({
    doc: lastValue,
    extensions: [
      lineNumbers(),
      highlightActiveLineGutter(),
      highlightActiveLine(),
      history(),
      keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
      indentOnInput(),
      bracketMatching(),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      langExtension(lang),
      darkTheme(props.isDark),
      EditorView.lineWrapping,
      props.readOnly ? EditorView.editable.of(false) : [],
      EditorView.updateListener.of((u) => {
        if (u.docChanged) {
          const v = u.state.doc.toString()
          lastValue = v
          emit('update:modelValue', v)
        }
      }),
    ],
  })
  view.value = new EditorView({ state, parent: host.value })
}

function applyTheme(isDark: boolean) {
  const v = view.value
  if (!v) return
  // 深浅色切换:重建配置里不含主题与语言,直接重新编译状态
  const lang = props.language || detectLanguage(props.filename || '')
  const state = EditorState.create({
    doc: v.state.doc.toString(),
    extensions: [
      lineNumbers(),
      highlightActiveLineGutter(),
      highlightActiveLine(),
      history(),
      keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
      indentOnInput(),
      bracketMatching(),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      langExtension(lang),
      darkTheme(isDark),
      EditorView.lineWrapping,
      props.readOnly ? EditorView.editable.of(false) : [],
      EditorView.updateListener.of((u) => {
        if (u.docChanged) {
          const cur = u.state.doc.toString()
          lastValue = cur
          emit('update:modelValue', cur)
        }
      }),
    ],
  })
  v.setState(state)
}

watch(() => [props.filename, props.language] as const, (arr) => {
  const [fn, lang] = arr
  const state = view.value?.state
  void state
  // 语言切换:重编译当前文档
  if (view.value) {
    const v = view.value
    const doc = v.state.doc.toString()
    const nextLang = lang || detectLanguage(fn || '')
    const st = EditorState.create({
      doc,
      extensions: [
        lineNumbers(),
        highlightActiveLineGutter(),
        highlightActiveLine(),
        history(),
        keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
        indentOnInput(),
        bracketMatching(),
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
        langExtension(nextLang),
        darkTheme(props.isDark),
        EditorView.lineWrapping,
        props.readOnly ? EditorView.editable.of(false) : [],
        EditorView.updateListener.of((up) => {
          if (up.docChanged) {
            const cur = up.state.doc.toString()
            lastValue = cur
            emit('update:modelValue', cur)
          }
        }),
      ],
    })
    v.setState(st)
  }
})

watch(() => props.isDark, (d) => applyTheme(!!d), { flush: 'post' })

watch(() => props.readOnly, (r) => {
  if (view.value) {
    view.value.setState(EditorState.create({
      doc: lastValue,
      extensions: [
        lineNumbers(), highlightActiveLineGutter(), highlightActiveLine(),
        history(),
        keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
        indentOnInput(), bracketMatching(),
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
        langExtension(props.language || detectLanguage(props.filename || '')),
        darkTheme(props.isDark),
        EditorView.lineWrapping,
        r ? EditorView.editable.of(false) : [],
        EditorView.updateListener.of((u) => {
          if (u.docChanged) {
            const cur = u.state.doc.toString()
            lastValue = cur
            emit('update:modelValue', cur)
          }
        }),
      ],
    }))
  }
})

// 受控值外部变更(如保存后重置)才回写,避免编辑中的每一次输入都触发重建
watch(() => props.modelValue, (nv) => {
  const v = view.value
  if (!v) return
  const cur = v.state.doc.toString()
  if (nv !== cur) {
    lastValue = nv || ''
    v.dispatch({ changes: { from: 0, to: cur.length, insert: lastValue } })
  }
})

onBeforeUnmount(() => {
  view.value?.destroy()
  view.value = null
})

watch(host, (el) => {
  if (el && !view.value) {
    build()
  }
}, { flush: 'post' })
</script>

<template>
  <div ref="host" class="code-editor" @focusout="emit('blur')"></div>
</template>

<style scoped>
.code-editor { height: 100%; width: 100%; overflow: auto; background: var(--chat-bg, transparent); }
.code-editor :deep(.cm-editor) { height: 100%; font-size: 13px; }
.code-editor :deep(.cm-editor.cm-focused) { outline: none; }
.code-editor :deep(.cm-scroller) { font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace; line-height: 1.55; }
</style>