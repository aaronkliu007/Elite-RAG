import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { KnowledgeDocument } from '../api/documents'
import DocumentReviewPanel from './DocumentReviewPanel.vue'

function document(overrides: Partial<KnowledgeDocument> = {}): KnowledgeDocument {
  return {
    id: 'document-1', spaceId: 'space-1', title: '云平台迁移实施方案',
    metadata: { classification: '内部', tags: ['迁移'] },
    versions: [{ id: 'version-1', versionNumber: 1, status: 'DRAFT', parseStatus: 'SUCCEEDED', createdAt: '2026-10-09T00:00:00Z', parseErrorCode: null }],
    currentPublishedVersionId: null, archived: false, reviews: [],
    allowedActions: ['VIEW', 'PREVIEW', 'DOWNLOAD', 'SUBMIT_REVIEW'],
    ...overrides,
  }
}

describe('真实文档审核操作', () => {
  it('编辑者仅在解析成功后提交审核', async () => {
    const pending = document({ versions: [{ id: 'version-1', versionNumber: 1, status: 'DRAFT', parseStatus: 'RUNNING', createdAt: '2026-10-09T00:00:00Z', parseErrorCode: null }] })
    const pendingWrapper = mount(DocumentReviewPanel, { props: { document: pending } })
    expect(pendingWrapper.get('[data-action="submit-review"]').attributes('disabled')).toBeDefined()

    const wrapper = mount(DocumentReviewPanel, { props: { document: document() } })
    await wrapper.get('[data-action="submit-review"]').trigger('click')
    expect(wrapper.emitted('submitReview')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('发布当前版本')
  })

  it('审核者可驳回或发布，且解析失败码显示中文', async () => {
    const failed = document({
      versions: [{ id: 'version-1', versionNumber: 1, status: 'IN_REVIEW', parseStatus: 'FAILED', createdAt: '2026-10-09T00:00:00Z', parseErrorCode: 'ENCRYPTED' }],
      allowedActions: ['VIEW', 'PREVIEW', 'DOWNLOAD', 'REJECT', 'PUBLISH'],
    })
    const wrapper = mount(DocumentReviewPanel, { props: { document: failed } })
    expect(wrapper.text()).toContain('文档已加密')
    expect(wrapper.get('[data-action="publish"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[name="rejectComment"]').setValue('请上传未加密版本')
    await wrapper.get('[data-action="reject"]').trigger('click')
    expect(wrapper.emitted('reject')?.[0]).toEqual(['请上传未加密版本'])
  })

  it('只读成员看不到任何写操作', () => {
    const wrapper = mount(DocumentReviewPanel, { props: { document: document({ allowedActions: ['VIEW', 'PREVIEW', 'DOWNLOAD'] }) } })
    expect(wrapper.find('[data-action="submit-review"]').exists()).toBe(false)
    expect(wrapper.find('[data-action="reject"]').exists()).toBe(false)
    expect(wrapper.find('[data-action="publish"]').exists()).toBe(false)
    expect(wrapper.get('a[data-action="preview"]').attributes('href')).toBe('/api/v1/documents/document-1/preview')
    expect(wrapper.get('a[data-action="download"]').attributes('href')).toBe('/api/v1/documents/document-1/download')
  })
})
