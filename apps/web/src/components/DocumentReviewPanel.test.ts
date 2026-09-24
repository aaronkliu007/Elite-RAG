import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { KnowledgeDocument } from '../api/knowledge'
import DocumentReviewPanel from './DocumentReviewPanel.vue'

function document(overrides: Partial<KnowledgeDocument> = {}): KnowledgeDocument {
  return {
    id: '00000000-0000-0000-0000-000000000901',
    spaceId: '00000000-0000-0000-0000-000000000902',
    title: '云平台迁移实施方案',
    ownerName: '解决方案中心',
    classification: '内部',
    metadataComplete: true,
    parseStatus: 'SUCCEEDED',
    lifecycleStatus: 'IN_REVIEW',
    parseProgress: 100,
    permissions: {
      canEdit: true,
      canPublish: true,
      canManageAcl: true,
    },
    parseErrors: [],
    preview: '第一章 项目背景',
    permissionScope: '解决方案中心及项目成员',
    versions: [
      {
        id: '00000000-0000-0000-0000-000000000903',
        number: 1,
        status: 'IN_REVIEW',
        parseStatus: 'SUCCEEDED',
        createdAt: '2026-09-24T10:00:00Z',
        createdByName: '张三',
      },
    ],
    ...overrides,
  }
}

describe('文档审核发布门禁', () => {
  it.each([
    ['解析中', document({ parseStatus: 'PENDING', parseProgress: 48 })],
    ['解析失败', document({ parseStatus: 'FAILED', parseErrors: [{ code: 'ENCRYPTED', message: '加密文档' }] })],
    ['元数据不完整', document({ metadataComplete: false })],
    [
      '服务端无发布权限',
      document({ permissions: { canEdit: true, canPublish: false, canManageAcl: true } }),
    ],
  ])('%s 时禁用发布', (_, value) => {
    const wrapper = mount(DocumentReviewPanel, { props: { document: value } })

    expect(wrapper.get('[data-testid="publish-button"]').attributes('disabled')).toBeDefined()
  })

  it('仅在解析成功、元数据完整且服务端允许时启用发布', async () => {
    const wrapper = mount(DocumentReviewPanel, { props: { document: document() } })
    const button = wrapper.get('[data-testid="publish-button"]')

    expect(button.attributes('disabled')).toBeUndefined()
    await button.trigger('click')
    expect(wrapper.emitted('publish')).toHaveLength(1)
  })

  it('编辑和权限入口只服从服务端权限位', () => {
    const wrapper = mount(DocumentReviewPanel, {
      props: {
        document: document({
          permissions: { canEdit: false, canPublish: true, canManageAcl: false },
        }),
      },
    })

    expect(wrapper.text()).not.toContain('编辑元数据')
    expect(wrapper.text()).not.toContain('管理权限')
    expect(wrapper.get('[data-testid="publish-button"]').attributes('disabled')).toBeUndefined()
  })
})
