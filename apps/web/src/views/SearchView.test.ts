import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { clearCsrfToken } from '../api/http'
import SearchView from './SearchView.vue'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('全文检索页', () => {
  beforeEach(() => { clearCsrfToken(); vi.restoreAllMocks() })

  it('可加载后续有检索权限的知识空间', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('page=2')) return json([{ id: 'later', name: '后续空间', allowedActions: ['SEARCH'] }])
      return json(Array.from({ length: 100 }, (_, i) => ({ id: `s${i}`, name: `空间${i}`, allowedActions: ['SEARCH'] })))
    }))
    const wrapper = mount(SearchView)
    await flushPromises()
    await wrapper.get('[data-action="more-search-spaces"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('后续空间')
    await wrapper.get('[name="spaceId"]').setValue('later')
    expect((wrapper.get('[name="spaceId"]').element as HTMLSelectElement).value).toBe('later')
    wrapper.unmount()
  })

  it('空间加载失败后允许重试，空权限时给出明确提示', async () => {
    let attempt = 0
    vi.stubGlobal('fetch', vi.fn(async () => {
      attempt++
      return attempt === 1
        ? json({ code: 'SERVICE_UNAVAILABLE', message: '空间服务不可用', traceId: 'space-error', fieldErrors: {} }, 503)
        : json([])
    }))
    const wrapper = mount(SearchView)
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('空间服务不可用')
    await wrapper.get('[data-action="retry-spaces"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('暂无可检索的知识空间')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

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
    expect(wrapper.text()).not.toContain('90%')
    await wrapper.get('[name="queryText"]').setValue('预算')
    expect(wrapper.get('[data-result-query]').text()).toContain('部署方案')
    expect(wrapper.get('[data-result-query]').text()).not.toContain('预算')
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

  it('允许用户明确限定一份已授权已发布文档，并在切换空间时清除原限定', async () => {
    const requests: Array<{ path: string, init?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      requests.push({ path, init })
      if (path === '/api/v1/knowledge-spaces?page=1&pageSize=100') return json([
        { id: 'space-1', name: '甲空间', allowedActions: ['SEARCH'] },
        { id: 'space-2', name: '乙空间', allowedActions: ['SEARCH'] },
      ])
      if (path.includes('/session/csrf')) return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-search' })
      if (path.includes('/space-1/documents')) return json([
        { id: 'doc-1', spaceId: 'space-1', title: '预算记录甲', currentPublishedVersionId: 'v1', archived: false, allowedActions: ['VIEW'] },
        { id: 'draft', spaceId: 'space-1', title: '未发布草稿', currentPublishedVersionId: null, archived: false, allowedActions: ['VIEW'] },
      ])
      if (path.includes('/space-2/documents')) return json([
        { id: 'doc-2', spaceId: 'space-2', title: '预算记录乙', currentPublishedVersionId: 'v2', archived: false, allowedActions: ['VIEW'] },
      ])
      if (path === '/api/v1/search') return json({ requestId: 'r', evidence: [], degraded: false,
        degradationReasons: [], stats: { lexicalCount: 0, vectorCount: 0, fusedCount: 0, reauthorizedCount: 0, selectedCount: 0 }, completedAt: '2026-10-09T00:00:00Z' })
      throw new Error(`unexpected request: ${path}`)
    }))
    const wrapper=mount(SearchView)
    await flushPromises()
    await wrapper.get('[name="scope"]').setValue('document')
    await flushPromises()
    expect(wrapper.text()).toContain('预算记录甲')
    expect(wrapper.text()).not.toContain('未发布草稿')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[name="documentId"]').setValue('doc-1')
    await wrapper.get('[name="queryText"]').setValue('预算')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const request=requests.find(item=>item.path==='/api/v1/search')
    expect(JSON.parse(String(request?.init?.body))).toMatchObject({ spaceId:'space-1', metadataFilter:{ documentId:'doc-1' } })
    await wrapper.get('[name="spaceId"]').setValue('space-2')
    await flushPromises()
    expect((wrapper.get('[name="documentId"]').element as HTMLSelectElement).value).toBe('')
    expect(wrapper.text()).toContain('预算记录乙')
    expect(wrapper.text()).not.toContain('预算记录甲')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })

  it('文档列表加载失败时禁止降级为整个空间检索，并允许重试', async () => {
    let attempts=0
    const search=vi.fn()
    vi.stubGlobal('fetch',vi.fn(async (input: RequestInfo | URL) => {
      const path=String(input)
      if(path.includes('/session/csrf'))return json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'csrf-search'})
      if(path === '/api/v1/knowledge-spaces?page=1&pageSize=100')return json([{id:'space-1',name:'空间',allowedActions:['SEARCH']}])
      if(path.includes('/documents')) {
        attempts++
        return attempts===1 ? json({code:'SERVICE_UNAVAILABLE',message:'加载文档失败',traceId:'test'},503)
          : json([{id:'doc-1',spaceId:'space-1',title:'已发布文档',currentPublishedVersionId:'v1',archived:false,allowedActions:['VIEW']}])
      }
      if(path==='/api/v1/search') { search(); return json({}) }
      throw new Error(`unexpected request: ${path}`)
    }))
    const wrapper=mount(SearchView)
    await flushPromises()
    await wrapper.get('[name="scope"]').setValue('document')
    await flushPromises()
    await wrapper.get('[name="queryText"]').setValue('预算')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[role="alert"]').text()).toContain('加载文档失败')
    expect(search).not.toHaveBeenCalled()
    await wrapper.get('button.more-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已发布文档')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })

  it('切换空间后忽略旧空间迟到的文档列表', async () => {
    let resolveOld: ((response: Response) => void) | undefined
    vi.stubGlobal('fetch',vi.fn(async (input: RequestInfo | URL) => {
      const path=String(input)
      if(path.includes('/session/csrf'))return json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'csrf-search'})
      if(path === '/api/v1/knowledge-spaces?page=1&pageSize=100')return json([
        {id:'space-1',name:'甲空间',allowedActions:['SEARCH']},{id:'space-2',name:'乙空间',allowedActions:['SEARCH']},
      ])
      if(path.includes('/space-1/documents'))return new Promise<Response>(resolve=>{resolveOld=resolve})
      if(path.includes('/space-2/documents'))return json([{id:'doc-2',spaceId:'space-2',title:'乙文档',currentPublishedVersionId:'v2',archived:false,allowedActions:['VIEW']}])
      throw new Error(`unexpected request: ${path}`)
    }))
    const wrapper=mount(SearchView)
    await flushPromises()
    await wrapper.get('[name="scope"]').setValue('document')
    await wrapper.get('[name="spaceId"]').setValue('space-2')
    await flushPromises()
    resolveOld?.(json([{id:'doc-1',spaceId:'space-1',title:'过期甲文档',currentPublishedVersionId:'v1',archived:false,allowedActions:['VIEW']}]))
    await flushPromises()
    expect(wrapper.text()).toContain('乙文档')
    expect(wrapper.text()).not.toContain('过期甲文档')
  })

  it('可继续加载超过一页的文档，已选文档不会被重置', async () => {
    const pageOne=Array.from({length:100},(_,index)=>({id:`doc-${index}`,spaceId:'space-1',title:`文档${index}`,
      currentPublishedVersionId:`v${index}`,archived:false,allowedActions:['VIEW']}))
    vi.stubGlobal('fetch',vi.fn(async (input: RequestInfo | URL) => {
      const path=String(input)
      if(path.includes('/session/csrf'))return json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'csrf-search'})
      if(path === '/api/v1/knowledge-spaces?page=1&pageSize=100')return json([{id:'space-1',name:'空间',allowedActions:['SEARCH']}])
      if(path.includes('/documents?page=1'))return json(pageOne)
      if(path.includes('/documents?page=2'))return json([{id:'doc-later',spaceId:'space-1',title:'后续文档',currentPublishedVersionId:'v101',archived:false,allowedActions:['VIEW']}])
      throw new Error(`unexpected request: ${path}`)
    }))
    const wrapper=mount(SearchView)
    await flushPromises()
    await wrapper.get('[name="scope"]').setValue('document')
    await flushPromises()
    await wrapper.get('[name="documentId"]').setValue('doc-0')
    await wrapper.get('button.more-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('后续文档')
    expect((wrapper.get('[name="documentId"]').element as HTMLSelectElement).value).toBe('doc-0')
    expect(wrapper.find('button.more-button').exists()).toBe(false)
  })
})
