/** SVG 线条兜底样式:AI 生成的 SVG 连线(path/polyline)常漏写 fill="none",
 *  而 SVG 的 fill 默认值是 black,浏览器会把开放折线与隐式闭合线围成的区域
 *  填成一大块黑色(表现为流程图上的"黑角/黑三角")。
 *  规则:有 stroke(描边线条)但未显式声明 fill 的元素强制 fill:none;
 *  显式写了 fill(含 style 内联)的元素不受影响,形状节点仍按原样填充。 */
export const SVG_LINE_FILL_FIX_CSS =
  'svg path[stroke]:not([fill]),svg polyline[stroke]:not([fill]){fill:none}'

/** 把兜底样式注入 HTML 文档:优先插到 </head> 前,无 head 时插到文档最前 */
export function injectSvgLineFillFix(html: string): string {
  const style = `<style>${SVG_LINE_FILL_FIX_CSS}</style>`
  if (/<\/head>/i.test(html)) return html.replace(/<\/head>/i, `${style}</head>`)
  return style + html
}
