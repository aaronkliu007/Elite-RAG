import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import { documentsApi } from '../api/documents'
import { spacesApi } from '../api/spaces'
import UploadPanel from '../components/UploadPanel.vue'
import UploadView from './UploadView.vue'

describe('文档上传页', () => {
  afterEach(() => vi.restoreAllMocks())

  it('加载后续空间后可以选择从空间详情传入的有权限目标', async () => {
    const makeSpace = (id: string) => ({ id, name: `空间${id}`, description: '', revision: 1, createdBy: 'u', createdAt: '', allowedActions: ['CREATE_DOCUMENT'] })
    vi.spyOn(spacesApi, 'listSpaces').mockResolvedValueOnce(Array.from({ length: 100 }, (_, i) => makeSpace(String(i))))
      .mockResolvedValueOnce([makeSpace('later')])
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/upload', component: UploadView }] })
    await router.push('/upload?spaceId=later'); await router.isReady()
    const wrapper = mount(UploadView, { global: { plugins: [router] } })
    await flushPromises()
    await wrapper.get('[data-action="more-upload-spaces"]').trigger('click'); await flushPromises()
    expect((wrapper.get('select[name="spaceId"]').element as HTMLSelectElement).value).toBe('later')
    wrapper.unmount()
  })

  it('空间加载失败时可重试，不误提示没有上传权限', async () => {
    vi.spyOn(spacesApi, 'listSpaces').mockRejectedValueOnce(new Error('网络故障')).mockResolvedValueOnce([])
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/upload', component: UploadView }] })
    await router.push('/upload'); await router.isReady()
    const wrapper = mount(UploadView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.find('[data-action="retry-spaces"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('没有可上传的知识空间')
    await wrapper.get('[data-action="retry-spaces"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('没有可上传的知识空间')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('网络失败后重试相同资料沿用幂等键，更改资料则使用新键', async () => {
    vi.spyOn(spacesApi, 'listSpaces').mockResolvedValue([])
    const upload = vi.spyOn(documentsApi, 'upload').mockRejectedValue(new Error('网络中断'))
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/upload', component: UploadView }] })
    await router.push('/upload'); await router.isReady()
    const wrapper = mount(UploadView, { global: { plugins: [router], stubs: { UploadPanel: { template: '<div />' } } } })
    await flushPromises()
    const file = new File(['内容'], '资料.md')
    const draft = { spaceId: 'space', title: '资料', classification: '内部', tags: ['项目'], file }
    wrapper.findComponent(UploadPanel).vm.$emit('submit', draft)
    await flushPromises()
    wrapper.findComponent(UploadPanel).vm.$emit('submit', { ...draft })
    await flushPromises()
    wrapper.findComponent(UploadPanel).vm.$emit('submit', { ...draft, title: '更新后的资料' })
    await flushPromises()
    expect(upload.mock.calls[0]?.[2]).toBe(upload.mock.calls[1]?.[2])
    expect(upload.mock.calls[2]?.[2]).not.toBe(upload.mock.calls[1]?.[2])
    wrapper.unmount()
  })
})
