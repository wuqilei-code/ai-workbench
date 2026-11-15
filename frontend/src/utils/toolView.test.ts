import { describe, it, expect } from 'vitest'
import type { ToolCall } from '@/types'
import {
  oneLine,
  parseSearchItems,
  splitPath,
  toolAction,
  toolDiff,
  toolKind,
  toolLinks,
  toolShell,
} from './toolView'

function tc(name: string, args?: unknown, output?: string): ToolCall {
  return { name, status: 'completed', args, output }
}

describe('utils/toolView', () => {
  it('classifies deepagents built-in tools', () => {
    expect(toolKind(tc('edit_file'))).toBe('diff')
    expect(toolKind(tc('write_file'))).toBe('diff')
    expect(toolKind(tc('execute'))).toBe('shell')
    expect(toolKind(tc('read_file'))).toBe('read')
    expect(toolKind(tc('ls'))).toBe('read')
    expect(toolKind(tc('grep'))).toBe('search')
    expect(toolKind(tc('glob'))).toBe('search')
    expect(toolKind(tc('webfetch'))).toBe('link')
    expect(toolKind(tc('update_memory'))).toBe('generic')
  })

  it('derives card actions', () => {
    expect(toolAction(tc('edit_file'))).toBe('edit')
    expect(toolAction(tc('write_file'))).toBe('write')
    expect(toolAction(tc('ls'))).toBe('list')
    expect(toolAction(tc('glob'))).toBe('glob')
    expect(toolAction(tc('web_search'))).toBe('websearch')
    expect(toolAction(tc('update_memory'))).toBeNull()
  })

  it('builds a diff view from edit_file arguments', () => {
    const view = toolDiff(
      tc('edit_file', {
        file_path: '/workspace/src/a.ts',
        old_string: 'const a = 1',
        new_string: 'const a = 2',
      }),
    )
    expect(view).not.toBeNull()
    expect(view!.mode).toBe('edit')
    expect(view!.path).toBe('/workspace/src/a.ts')
    expect(view!.diff.additions).toBe(1)
    expect(view!.diff.deletions).toBe(1)
  })

  it('treats write_file content as added lines and flags replace_all', () => {
    const write = toolDiff(tc('write_file', { file_path: 'a.md', content: '# hi' }))
    expect(write!.mode).toBe('write')
    expect(write!.diff.additions).toBe(1)
    expect(write!.diff.deletions).toBe(0)

    const edit = toolDiff(
      tc('edit_file', { file_path: 'a', old_string: 'x', new_string: 'y', replace_all: true }),
    )
    expect(edit!.replaceAll).toBe(true)
  })

  it('extracts shell commands', () => {
    expect(toolShell(tc('execute', { command: 'npm test', timeout: 30 }))).toEqual({
      command: 'npm test',
      timeout: 30,
    })
    expect(toolShell(tc('update_memory', { section: 'x' }))).toBeNull()
  })

  it('parses grep/glob/list output into path rows', () => {
    const grep = parseSearchItems('src/a.ts:12:const x = 1\nsrc/b.ts:3:hello')
    expect(grep).toEqual([
      { path: 'src/a.ts', line: 12, detail: 'const x = 1' },
      { path: 'src/b.ts', line: 3, detail: 'hello' },
    ])

    const glob = parseSearchItems('- src/one.ts\n- src/two.ts\nFound 2 files')
    expect(glob.map((i) => i.path)).toEqual(['src/one.ts', 'src/two.ts'])
  })

  it('collects clickable links from args and output', () => {
    const view = toolLinks(
      tc('web_search', { query: 'vue 3' }, 'see https://vuejs.org/guide and https://vuejs.org/guide.'),
    )
    expect(view.query).toBe('vue 3')
    expect(view.urls).toEqual(['https://vuejs.org/guide'])
  })

  it('splits and truncates helper strings', () => {
    expect(splitPath('src/components/a.vue')).toEqual({ dir: 'src/components/', base: 'a.vue' })
    expect(splitPath('a.vue')).toEqual({ dir: '', base: 'a.vue' })
    expect(oneLine('a\n  b   c')).toBe('a b c')
    expect(oneLine('x'.repeat(50), 10)).toBe('xxxxxxxxx…')
  })
})
