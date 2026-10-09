import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import UploadPanel from './UploadPanel.vue'

const spaces = [{
  id: 'space-1', name: '研发知识空间', description: '技术资产', revision: 1,
  createdBy: 'user-1', createdAt: '2026-10-09T00:00:00Z',
  allowedActions: ['VIEW', 'CREATE_DOCUMENT'],
}]

describe('真实空间文档上传表单', () => {
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

  it('没有可创建文档的空间时保持禁用并给出权限说明', () => {
    const wrapper = mount(UploadPanel, { props: { spaces: [{ ...spaces[0], allowedActions: ['VIEW'] }] } })
    expect(wrapper.get('[data-testid="upload-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('没有可上传的知识空间')
  })
})
