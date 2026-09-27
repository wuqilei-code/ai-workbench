// markdown-it 无内置类型声明(且离线环境不便装 @types/markdown-it),这里给出最小 shim。
declare module 'markdown-it' {
  class MarkdownIt {
    constructor(options?: any)
    render(src: string, env?: unknown): string
    use(plugin: unknown, ...params: unknown[]): MarkdownIt
    utils: any
    core: any
    [key: string]: any
  }
  export default MarkdownIt
}
