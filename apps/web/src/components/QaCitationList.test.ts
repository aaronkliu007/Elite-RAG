import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import QaCitationList from './QaCitationList.vue'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const citations = [{
  claimId: '10000000-0000-7000-8000-000000000001',
  text: `跨区域知识资产已经完成统一发布与复用${'超长声明'.repeat(24)}`,
  evidenceIds: ['20000000-0000-7000-8000-000000000001'],
}]

describe('问答引用列表', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('先对来源再次鉴权，成功后才打开安全预览地址', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (!String(input).includes('/sources/')) throw new Error('unexpected request')
      return json({
        documentId: '30000000-0000-7000-8000-000000000001',
        displayName: `制造行业解决方案${'超长标题'.repeat(16)}`,
        versionId: '40000000-0000-7000-8000-000000000001',
        location: { type: 'PAGE', coordinates: { pageNumber: 18 } },
        previewUrl: '/api/v1/documents/30000000-0000-7000-8000-000000000001/preview',
        downloadUrl: '/api/v1/documents/30000000-0000-7000-8000-000000000001/download',
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const wrapper = mount(QaCitationList, { props: { requestId: 'request-1', citations } })

    await wrapper.get('[data-source-action]').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledOnce()
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/qa/questions/request-1/sources/20000000-0000-7000-8000-000000000001')
    expect(open).toHaveBeenCalledWith('/api/v1/documents/30000000-0000-7000-8000-000000000001/preview', '_blank', 'noopener,noreferrer')
    expect(wrapper.get('[data-source-title]').attributes('title')).toContain('制造行业解决方案')
    expect(wrapper.get('[data-citation-claim]').attributes('title')).toBe(citations[0]?.text)
  })

  it('来源撤权与不存在使用同一提示且不泄露返回细节', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({
      code: 'NOT_FOUND', message: '绝密客户项目已经被撤销', traceId: 'trace-source', fieldErrors: {},
    }, 404)))
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const wrapper = mount(QaCitationList, { props: { requestId: 'request-2', citations } })

    await wrapper.get('[data-source-action]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe('来源不存在或你已无权访问')
    expect(wrapper.text()).not.toContain('绝密客户项目')
    expect(wrapper.text()).not.toContain('trace-source')
    expect(open).not.toHaveBeenCalled()
  })

  it('再次鉴权失败时清除先前获准显示的来源信息', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({
        documentId: 'document-1', displayName: '曾授权的来源标题', versionId: 'version-1',
        location: { type: 'PAGE', coordinates: { pageNumber: 18 } },
        previewUrl: '/api/v1/documents/document-1/preview',
        downloadUrl: '/api/v1/documents/document-1/download',
      }))
      .mockResolvedValueOnce(json({ code: 'NOT_FOUND' }, 404))
    vi.stubGlobal('fetch', fetchMock)
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const wrapper = mount(QaCitationList, { props: { requestId: 'request-1', citations } })

    await wrapper.get('[data-source-action]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('曾授权的来源标题')

    await wrapper.get('[data-source-action]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-source-title]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('曾授权的来源标题')
    expect(wrapper.get('[role="alert"]').text()).toBe('来源不存在或你已无权访问')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(open).toHaveBeenCalledOnce()
  })

  it('为引用网格和长连续标识符提供可收缩布局契约', () => {
    const wrapper = mount(QaCitationList, { props: {
      requestId: 'request-3',
      citations: [{ ...citations[0]!, text: 'https://knowledge.example.internal/' + 'unbroken-token-'.repeat(24) }],
    } })

    expect(wrapper.get('[data-citation-list]').classes()).toContain('citation-grid')
    expect(wrapper.get('[data-citation-claim]').attributes('title')).toContain('unbroken-token')
    expect(wrapper.findAll('[data-source-action]')).toHaveLength(1)
  })
})
