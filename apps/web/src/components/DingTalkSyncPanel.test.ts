import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { dingtalkApi } from '../api/dingtalk'
import DingTalkSyncPanel from './DingTalkSyncPanel.vue'

afterEach(() => vi.restoreAllMocks())
it('未配置时显示接入状态且不可同步', async () => {
  vi.spyOn(dingtalkApi, 'status').mockResolvedValue({ configured: false, authoritativeScopeConfirmed: false })
  const wrapper = mount(DingTalkSyncPanel)
  await flushPromises()
  expect(wrapper.text()).toContain('钉钉尚未配置')
  expect(wrapper.find('[data-action="dingtalk-sync"]').exists()).toBe(false)
})
it('预览后才允许确认范围的全量同步', async () => {
  vi.spyOn(dingtalkApi, 'status').mockResolvedValue({ configured: true, authoritativeScopeConfirmed: true })
  vi.spyOn(dingtalkApi, 'preview').mockResolvedValue({ departmentCount: 3, userCount: 15, capturedAt: '2026-11-20T00:00:00Z' })
  const sync = vi.spyOn(dingtalkApi, 'sync').mockResolvedValue({ departmentCount: 3, userCount: 15, capturedAt: '2026-11-20T00:00:00Z' })
  const wrapper = mount(DingTalkSyncPanel)
  await flushPromises()
  expect(wrapper.get('[data-action="dingtalk-sync"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[data-action="dingtalk-preview"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('15 位成员')
  await wrapper.get('[data-action="dingtalk-sync"]').trigger('click'); await flushPromises()
  expect(sync).toHaveBeenCalledTimes(1)
})
it('组织架构按父子层级显示', async () => {
  vi.spyOn(dingtalkApi, 'status').mockResolvedValue({ configured: true, authoritativeScopeConfirmed: false })
  vi.spyOn(dingtalkApi, 'departments').mockResolvedValue([{ id: 'c', parentId: 'p', name: '研发部' }, { id: 'p', parentId: null, name: '公司' }])
  const wrapper = mount(DingTalkSyncPanel)
  await flushPromises()
  await wrapper.get('button[aria-expanded="false"]').trigger('click'); await flushPromises()
  const nodes = wrapper.findAll('[aria-label="钉钉部门列表"] li')
  expect(nodes.map(node => node.text())).toEqual(['公司', '研发部'])
  expect(nodes[1]?.attributes('style')).toContain('16px')
})
