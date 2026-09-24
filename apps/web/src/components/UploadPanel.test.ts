import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import UploadPanel from './UploadPanel.vue'

describe('受控上传表单', () => {
  it('必填元数据和文件全部齐全后才允许提交', async () => {
    const wrapper = mount(UploadPanel)
    const submit = wrapper.get('[data-testid="upload-submit"]')
    expect(submit.attributes('disabled')).toBeDefined()

    await wrapper.get('[name="title"]').setValue('制造行业解决方案')
    await wrapper.get('[name="ownerName"]').setValue('行业产品中心')
    await wrapper.get('[name="classification"]').setValue('内部')
    await wrapper.get('[name="permissionScope"]').setValue('行业产品中心')
    const fileInput = wrapper.get<HTMLInputElement>('[name="file"]')
    const file = new File(['# 方案'], '方案.md', { type: 'text/markdown' })
    Object.defineProperty(fileInput.element, 'files', { value: [file] })
    await fileInput.trigger('change')

    expect(submit.attributes('disabled')).toBeUndefined()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')?.[0]?.[0]).toMatchObject({
      title: '制造行业解决方案',
      ownerName: '行业产品中心',
      classification: '内部',
      permissionScope: '行业产品中心',
      file,
    })
  })
})
