import { describe, it, expect } from 'vitest'
import { i18n, messages, applyDocumentLang } from './index'

/** 递归收集所有叶子 key(点分路径)。 */
function leafKeys(obj: Record<string, any>, prefix = ''): string[] {
  const out: string[] = []
  for (const [k, v] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object') out.push(...leafKeys(v, path))
    else out.push(path)
  }
  return out
}

function hasKey(locale: 'zh' | 'en', key: string): boolean {
  let cur: any = (messages as any)[locale]
  for (const seg of key.split('.')) {
    if (cur == null || typeof cur !== 'object' || !(seg in cur)) return false
    cur = cur[seg]
  }
  return typeof cur === 'string'
}

describe('i18n messages', () => {
  it('every message compiles and resolves (guards against vue-i18n syntax errors)', () => {
    for (const locale of ['zh', 'en'] as const) {
      ;(i18n.global.locale as unknown as { value: 'zh' | 'en' }).value = locale
      for (const key of leafKeys(messages[locale])) {
        // t() 会触发消息编译:含未转义的 @ / | / { 等会在此抛 "Invalid linked format"
        const raw = key.split('.').reduce<any>((o, k) => o?.[k], (messages as any)[locale])
        // 整条文案就是一个占位符时(如 en 的 '{n}'),无参解析为空串是预期行为,
        // 改为带一个哑元参数编译,仍能捕获语法错误。
        const onlyPlaceholder = typeof raw === 'string' && /^\s*\{[^}]+\}\s*$/.test(raw)
        const val = onlyPlaceholder
          ? i18n.global.t(key, { [raw.trim().slice(1, -1).trim()]: 'X' })
          : i18n.global.t(key)
        expect(typeof val).toBe('string')
        expect(val.length, `empty message for "${locale}:${key}"`).toBeGreaterThan(0)
      }
    }
    ;(i18n.global.locale as unknown as { value: 'zh' | 'en' }).value = 'zh'
  })

  it('placeholder keeps the literal @ (escaped as {"@"})', () => {
    const zh = i18n.global.t('input.placeholder')
    expect(zh).toContain('@')
  })

  it('applyDocumentLang does not throw without document (SSR/node)', () => {
    expect(() => applyDocumentLang('en')).not.toThrow()
    expect(() => applyDocumentLang('zh')).not.toThrow()
  })

  /**
   * zh / en 两个字典的命名空间与 key 集合必须完全一致。
   * 之前 zh 侧因少了一个 `admin: {` 的闭合花括号,导致 automation2/dashboard/... 共 24 个
   * 命名空间被嵌进了 admin 内部 —— 语法合法、tsc 不报错、也没有任何其他测试能发现,
   * 表现为界面上一批文案退化成裸 key。这里从结构上做双向比对兜住。
   */
  it('zh and en expose exactly the same namespaces and leaf keys', () => {
    const zhSet = new Set(leafKeys(messages.zh))
    const enSet = new Set(leafKeys(messages.en))
    const onlyZh = [...zhSet].filter((k) => !enSet.has(k)).sort()
    const onlyEn = [...enSet].filter((k) => !zhSet.has(k)).sort()
    expect(
      { onlyZh, onlyEn },
      `zh/en 字典不一致\n仅 zh 有:${onlyZh.join(', ') || '(无)'}\n仅 en 有:${onlyEn.join(', ') || '(无)'}`,
    ).toEqual({ onlyZh: [], onlyEn: [] })
  })

  /**
   * 反向校验:源码里 `t('x.y')` / `tr('x.y')` 引用的 key 必须同时存在于 zh 与 en。
   * 字典侧的用例只能保证"存在的 key 能编译",发现不了"模板引用了字典里没有的 key"——
   * 那种情况 vue-i18n 会把 key 本身当文案渲染到界面上(如 n-transfer 标题显示成
   * `pickers2.skillSourceTitle`),没有任何其他测试能兜住。
   */
  it('every t()/tr() key referenced in source exists in both locales', () => {
    // 用 Vite 的 import.meta.glob 读取源码原文(不依赖 node:fs,故无需 @types/node)
    const files = import.meta.glob('../**/*.{vue,ts}', {
      eager: true,
      query: '?raw',
      import: 'default',
    }) as Record<string, string>

    const missing = new Map<string, string[]>()
    const re = /\b(?:t|tr)\(\s*'([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*)'/g
    for (const [file, text] of Object.entries(files)) {
      if (file.endsWith('i18n/i18n.test.ts') || file.endsWith('i18n/index.ts')) continue
      for (const m of text.matchAll(re)) {
        const key = m[1]
        const bad = (['zh', 'en'] as const).filter((l) => !hasKey(l, key))
        if (bad.length) {
          const list = missing.get(key) ?? []
          list.push(`${file}[${bad.join(',')}]`)
          missing.set(key, list)
        }
      }
    }
    const detail = Array.from(missing.entries())
      .map(([k, where]) => `${k}  <- ${where.join(' | ')}`)
      .join('\n')
    expect(missing.size, `i18n key 缺失(zh/en):\n${detail}`).toBe(0)
  })
})
