import type { SolutionLocator } from './solutions'

export function formatSolutionLocator(locator: SolutionLocator): string {
  switch (locator.type) {
    case 'PAGE': return `第 ${locator.pageNumber} 页`
    case 'SLIDE': return `第 ${locator.slideNumber} 张幻灯片`
    case 'TABLE': return `${locator.tableName} · 第 ${locator.startRow}–${locator.endRow} 行 · 第 ${locator.startColumn}–${locator.endColumn} 列`
    case 'MARKDOWN': return locator.headingPath.join(' / ') || (locator.nodeId ? `段落 ${locator.nodeId}` : '文档段落')
  }
}
