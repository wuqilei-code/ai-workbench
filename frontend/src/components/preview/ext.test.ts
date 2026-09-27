import { describe, it, expect } from 'vitest'
import { extToKind, extToLang, fmtSize, EDITABLE_KINDS, BINARY_KINDS } from './ext'

describe('preview/ext', () => {
  it('maps file extensions to preview kinds', () => {
    expect(extToKind('a.json')).toBe('code')
    expect(extToKind('a.png')).toBe('image')
    expect(extToKind('a.pdf')).toBe('pdf')
    expect(extToKind('a.md')).toBe('markdown')
    expect(extToKind('dir/noext')).toBe('unsupported')
  })

  it('maps file extensions to highlight.js languages', () => {
    expect(extToLang('a.ts')).toBe('typescript')
    expect(extToLang('a.json')).toBe('json')
    expect(extToLang('a.unknown')).toBeUndefined()
  })

  it('formats sizes', () => {
    expect(fmtSize(0)).toBe('-')
    expect(fmtSize(512)).toBe('512 B')
    expect(fmtSize(2048)).toBe('2.0 KB')
    expect(fmtSize(1048576)).toBe('1.0 MB')
  })

  it('exposes editable/binary kind sets', () => {
    expect(EDITABLE_KINDS.has('code')).toBe(true)
    expect(BINARY_KINDS.has('image')).toBe(true)
    expect(BINARY_KINDS.has('code')).toBe(false)
  })
})
