/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

declare module 'xlsx-preview' {
  type XlsxData = Blob | File | ArrayBuffer
  interface XlsxOptions {
    output?: 'string' | 'arrayBuffer'
    separateSheets?: boolean
    minimumRows?: number
    minimumCols?: number
  }
  export function xlsx2Html(
    data: XlsxData,
    options?: XlsxOptions
  ): Promise<string | ArrayBuffer | string[] | Promise<ArrayBuffer>[]>
  const _default: { xlsx2Html: typeof xlsx2Html }
  export default _default
}
