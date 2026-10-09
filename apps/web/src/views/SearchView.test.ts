import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { clearCsrfToken } from '../api/http'
import SearchView from './SearchView.vue'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('全文检索页', () => {
  beforeEach(() => { clearCsrfToken(); vi.restoreAllMocks() })

  it('显示降级提示和四类可跳转来源定位', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path === '/api/v1/knowledge-spaces?page=1&pageSize=100') return json([{ id: 'space-1', name: '方案空间', description: '', revision: 1, createdBy: 'user-1', createdAt: '2026-10-09T00:00:00Z', allowedActions: ['VIEW', 'SEARCH'] }])
      if (path === '/api/v1/session/csrf') return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-search' })
      if (path === '/api/v1/search') return json({
        requestId: 'request-1', degraded: true, degradationReasons: ['VECTOR_UNAVAILABLE'], completedAt: '2026-10-09T00:00:00Z',
        stats: { lexicalCount: 4, vectorCount: 0, fusedCount: 4, reauthorizedCount: 4, selectedCount: 4 },
        evidence: [
          { evidenceId: 'e1', documentId: 'd1', versionId: 'v1', nodeId: 'n1', title: 'PDF', excerpt: '页来源', score: .9, locator: { type: 'PAGE', pageNumber: 3, boundingBox: null } },
          { evidenceId: 'e2', documentId: 'd2', versionId: 'v2', nodeId: 'n2', title: 'PPT', excerpt: '幻灯片来源', score: .8, locator: { type: 'SLIDE', slideNumber: 5 } },
          { evidenceId: 'e3', documentId: 'd3', versionId: 'v3', nodeId: 'n3', title: 'Excel', excerpt: '表格来源', score: .7, locator: { type: 'TABLE', tableName: '预算表', startRow: 2, endRow: 6, startColumn: 1, endColumn: 4 } },
          { evidenceId: 'e4', documentId: 'd4', versionId: 'v4', nodeId: 'n4', title: 'Markdown', excerpt: '章节来源', score: .6, locator: { type: 'MARKDOWN', headingPath: ['架构', '部署'], nodeId: 'n4' } },
        ],
      })
      throw new Error(`unexpected request: ${path}`)
    }))
    const wrapper = mount(SearchView)
    await flushPromises()
    await wrapper.get('[name="queryText"]').setValue('部署方案')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-state="degraded"]').text()).toContain('检索已降级')
    expect(wrapper.text()).toContain('PDF 第 3 页')
    expect(wrapper.text()).toContain('PPT 第 5 页')
    expect(wrapper.text()).toContain('预算表：第 2–6 行，第 1–4 列')
    expect(wrapper.text()).toContain('架构 / 部署')
    expect(wrapper.findAll('a[data-source-link]')).toHaveLength(4)
  })

  it('呈现加载、空结果和服务失败状态', async () => {
    let searchAttempt = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path.includes('/knowledge-spaces')) return json([{ id: 'space-1', name: '方案空间', description: '', revision: 1, createdBy: 'user-1', createdAt: '2026-10-09T00:00:00Z', allowedActions: ['SEARCH'] }])
      if (path.includes('/session/csrf')) return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-search' })
      searchAttempt += 1
      if (searchAttempt === 1) return json({ requestId: 'r', evidence: [], degraded: false, degradationReasons: [], stats: { lexicalCount: 0, vectorCount: 0, fusedCount: 0, reauthorizedCount: 0, selectedCount: 0 }, completedAt: '2026-10-09T00:00:00Z' })
      return json({ code: 'SERVICE_UNAVAILABLE', message: '检索服务暂时不可用', traceId: 'trace-search', fieldErrors: {} }, 503)
    }))
    const wrapper = mount(SearchView)
    await flushPromises()
    await wrapper.get('[name="queryText"]').setValue('不存在')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('没有找到可访问的来源')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('trace-search')
  })
})
