import { describe, it, expect } from 'vitest'
import { contentAsDiff, diffLines, parseUnifiedPatch } from './diff'

describe('utils/diff', () => {
  it('reports no changes for identical text (collapsed into a gap)', () => {
    const d = diffLines('a\nb\nc', 'a\nb\nc')
    expect(d.additions).toBe(0)
    expect(d.deletions).toBe(0)
    expect(d.rows).toEqual([{ type: 'gap', count: 3 }])
  })

  it('detects a changed line and keeps surrounding context', () => {
    const d = diffLines('a\nb\nc\nd', 'a\nb\nX\nd')
    expect(d.additions).toBe(1)
    expect(d.deletions).toBe(1)
    expect(d.rows.map((r) => r.type)).toEqual(['ctx', 'ctx', 'del', 'add', 'ctx'])
    expect(d.rows).toMatchObject([
      { type: 'ctx', text: 'a', oldNo: 1, newNo: 1 },
      { type: 'ctx', text: 'b', oldNo: 2, newNo: 2 },
      { type: 'del', text: 'c', oldNo: 3 },
      { type: 'add', text: 'X', newNo: 3 },
      { type: 'ctx', text: 'd', oldNo: 4, newNo: 4 },
    ])
  })

  it('handles pure insertions and deletions', () => {
    const add = diffLines('a\nb', 'a\nx\nb')
    expect(add.additions).toBe(1)
    expect(add.deletions).toBe(0)
    expect(add.rows).toMatchObject([
      { type: 'ctx', text: 'a' },
      { type: 'add', text: 'x', newNo: 2 },
      { type: 'ctx', text: 'b' },
    ])

    const del = diffLines('a\nx\nb', 'a\nb')
    expect(del.additions).toBe(0)
    expect(del.deletions).toBe(1)
    expect(del.rows).toMatchObject([
      { type: 'ctx', text: 'a' },
      { type: 'del', text: 'x', oldNo: 2 },
      { type: 'ctx', text: 'b' },
    ])
  })

  it('collapses long unchanged regions between changes', () => {
    const before = Array.from({ length: 40 }, (_, i) => `line ${i}`).join('\n')
    const after = before.replace('line 5', 'line 5 changed').replace('line 35', 'line 35 changed')
    const d = diffLines(before, after)
    const gaps = d.rows.filter((r) => r.type === 'gap') as { type: 'gap'; count: number }[]
    // 两处改动之间的大段上下文被折叠
    expect(gaps.length).toBe(1)
    expect(gaps[0].count).toBeGreaterThan(20)
    expect(d.additions).toBe(2)
    expect(d.deletions).toBe(2)
  })

  it('treats whole-file content as additions', () => {
    const d = contentAsDiff('one\ntwo\n')
    expect(d.additions).toBe(2)
    expect(d.deletions).toBe(0)
    expect(d.rows).toEqual([
      { type: 'add', text: 'one', newNo: 1 },
      { type: 'add', text: 'two', newNo: 2 },
    ])
  })

  it('truncates very long content and reports hidden lines', () => {
    const content = Array.from({ length: 700 }, (_, i) => `line ${i}`).join('\n')
    const d = contentAsDiff(content)
    expect(d.truncated).toBe(true)
    expect(d.rows.length).toBe(600)
    expect(d.hiddenLines).toBe(100)
    expect(d.additions).toBe(700)
  })

  it('parses unified patches with hunk line numbers', () => {
    const d = parseUnifiedPatch(
      ['--- a/x.ts', '+++ b/x.ts', '@@ -10,2 +10,3 @@', ' same', '-old', '+new', '+extra', ''].join('\n'),
    )
    expect(d).not.toBeNull()
    expect(d!.additions).toBe(2)
    expect(d!.deletions).toBe(1)
    expect(d!.rows).toMatchObject([
      { type: 'ctx', text: 'same', oldNo: 10, newNo: 10 },
      { type: 'del', text: 'old', oldNo: 11 },
      { type: 'add', text: 'new', newNo: 11 },
      { type: 'add', text: 'extra', newNo: 12 },
    ])
  })

  it('returns null for text without any change lines', () => {
    expect(parseUnifiedPatch('just some text')).toBeNull()
  })
})
