import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import { documentsApi, type KnowledgeDocument } from '../api/documents'
import { ApiRequestError } from '../api/http'
import DocumentDetailView from './DocumentDetailView.vue'

const document = (id: string): KnowledgeDocument => ({
  id, spaceId: 'space', title: `文档${id}`, metadata: { classification: '', tags: [] }, versions: [],
  currentPublishedVersionId: null, archived: false, reviews: [], allowedActions: ['VIEW'],
})

describe('文档详情', () => {
  afterEach(() => vi.restoreAllMocks())

  it('从检索来源进入时说明预览不会自动定位片段', async () => {
    vi.spyOn(documentsApi, 'get').mockResolvedValue(document('A'))
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/documents/:documentId', component: DocumentDetailView }] })
    await router.push('/documents/A?source=%23%2Ftexts%2F0'); await router.isReady()
    const wrapper = mount(DocumentDetailView, { global: { plugins: [router], stubs: { DocumentReviewPanel: true } } })
    await flushPromises()
    expect(wrapper.get('[data-source-location-note]').text()).toContain('不会自动定位')
    wrapper.unmount()
  })

  it('切换文档时清除旧详情并忽略旧请求的迟到结果', async () => {
    let finishOld!: (value: KnowledgeDocument) => void
    const oldRequest = new Promise<KnowledgeDocument>((resolve) => { finishOld = resolve })
    vi.spyOn(documentsApi, 'get').mockReturnValueOnce(oldRequest).mockResolvedValueOnce(document('B'))
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/documents/:documentId', component: DocumentDetailView }] })
    await router.push('/documents/A')
    await router.isReady()
    const wrapper = mount(DocumentDetailView, { global: { plugins: [router], stubs: { DocumentReviewPanel: { props: ['document'], template: '<div>{{ document.title }}</div>' } } } })
    await router.push('/documents/B')
    await flushPromises()
    expect(wrapper.text()).toContain('文档B')
    finishOld(document('A'))
    await flushPromises()
    expect(wrapper.text()).toContain('文档B')
    expect(wrapper.text()).not.toContain('文档A')
    wrapper.unmount()
  })

  it('操作时发现权限撤销后隐藏已展示的文档', async () => {
    const existing = document('A')
    existing.versions = [{ id: 'version', versionNumber: 1, status: 'DRAFT', parseStatus: 'SUCCEEDED', createdAt: '', parseErrorCode: null }]
    vi.spyOn(documentsApi, 'get').mockResolvedValue(existing)
    vi.spyOn(documentsApi, 'submitReview').mockRejectedValue(new ApiRequestError('资源不存在或无权访问', 404, 'NOT_FOUND', '', {}))
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/documents/:documentId', component: DocumentDetailView }] })
    await router.push('/documents/A')
    await router.isReady()
    const wrapper = mount(DocumentDetailView, { global: { plugins: [router], stubs: { DocumentReviewPanel: { props: ['document'], template: '<button @click="$emit(\'submit-review\')">{{ document.title }}</button>' } } } })
    await flushPromises()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('文档A')
    expect(wrapper.get('[role="alert"]').text()).toContain('资源不存在或无权访问')
    wrapper.unmount()
  })
})
