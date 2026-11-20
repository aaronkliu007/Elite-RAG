import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import UploadPanel from './UploadPanel.vue'

const spaces = [{
  id: 'space-1', name: '研发知识空间', description: '技术资产', revision: 1,
  createdBy: 'user-1', createdAt: '2026-10-09T00:00:00Z',
  allowedActions: ['VIEW', 'CREATE_DOCUMENT'],
}]

describe('真实空间文档上传表单', () => {
  it('尚有空间未加载时，不误报没有上传权限', () => {
    const wrapper = mount(UploadPanel, { props: { spaces: [], moreSpacesAvailable: true } })
    expect(wrapper.text()).toContain('继续加载后续空间')
    expect(wrapper.text()).not.toContain('没有可上传的知识空间')
  })
  it('空间、标题、密级和文件齐全后提交后端支持的字段', async () => {
    const wrapper = mount(UploadPanel, { props: { spaces } })
    const submit = wrapper.get('[data-testid="upload-submit"]')
    expect(submit.attributes('disabled')).toBeDefined()
    expect(wrapper.get('input[type="file"]').attributes('accept')).toBe('.docx,.xlsx,.pdf,.pptx,.md,.png')
    expect(wrapper.text()).toContain('100 MB')

    await wrapper.get('[name="spaceId"]').setValue('space-1')
    await wrapper.get('[name="title"]').setValue('制造行业解决方案')
    await wrapper.get('[name="classification"]').setValue('内部')
    await wrapper.get('[name="tags"]').setValue('制造, 运维')
    const fileInput = wrapper.get<HTMLInputElement>('[name="file"]')
    const file = new File(['# 方案'], '方案.md', { type: 'text/markdown' })
    Object.defineProperty(fileInput.element, 'files', { value: [file] })
    await fileInput.trigger('change')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('submit')?.[0]?.[0]).toEqual({
      spaceId: 'space-1', title: '制造行业解决方案', classification: '内部',
      tags: ['制造', '运维'], file,
    })
  })

  it('仅预选当前有上传权限的目标空间', () => {
    const allowed = mount(UploadPanel, { props: { spaces, initialSpaceId: 'space-1' } })
    expect((allowed.get('[name="spaceId"]').element as HTMLSelectElement).value).toBe('space-1')
    const denied = mount(UploadPanel, { props: { spaces: [{ ...spaces[0], allowedActions: ['VIEW'] }], initialSpaceId: 'space-1' } })
    expect((denied.get('[name="spaceId"]').element as HTMLSelectElement).value).toBe('')
  })

  it('没有可创建文档的空间时保持禁用并给出权限说明', () => {
    const wrapper = mount(UploadPanel, { props: { spaces: [{ ...spaces[0], allowedActions: ['VIEW'] }] } })
    expect(wrapper.get('[data-testid="upload-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('没有可上传的知识空间')
  })
})
