import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import SolutionListView from './SolutionListView.vue'
import { spacesApi } from '../api/spaces'
import { solutionsApi, type SolutionSummary } from '../api/solutions'

const space = {
  id: 'space-1', name: '方案空间', description: '', revision: 0,
  createdBy: 'user-1', createdAt: '2026-10-28T10:00:00Z',
  allowedActions: ['VIEW', 'CREATE_DOCUMENT'],
}

const json = (body: unknown) => new Response(JSON.stringify(body), {
  headers: { 'Content-Type': 'application/json' },
})

beforeEach(() => vi.restoreAllMocks())

it('列出有权空间的方案并显示创建入口和长标题完整提示', async () => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input)
    if (path.includes('/knowledge-spaces?page=')) return json([space])
    if (path.includes('/knowledge-spaces/space-1/solutions')) return json([{
      id: 'solution-1', spaceId: 'space-1', name: '超长制造业智能运维解决方案',
      phase: 'REQUIREMENTS_DRAFT', revision: 0, createdAt: space.createdAt, updatedAt: space.createdAt,
    }])
    throw new Error(path)
  }))
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/spaces/:spaceId/solutions', component: SolutionListView },
    { path: '/solutions/:solutionId', component: { template: '<div />' } },
  ] })
  await router.push('/spaces/space-1/solutions')
  await router.isReady()
  const wrapper = mount(SolutionListView, { global: { plugins: [router] } })
  await flushPromises()
  expect(wrapper.get('[data-solution-name]').attributes('title')).toBe('超长制造业智能运维解决方案')
  expect(wrapper.find('[data-form="create-solution"]').exists()).toBe(true)
})

it('切换空间时忽略旧空间的迟到方案列表', async () => {
  vi.spyOn(spacesApi, 'listSpaces').mockResolvedValue([space, { ...space, id: 'space-2', name: '另一空间' }])
  let finishOld!: (value: SolutionSummary[]) => void
  const delayedOld = new Promise<SolutionSummary[]>((resolve) => { finishOld = resolve })
  const entry = (id: string, spaceId: string): SolutionSummary => ({ id, spaceId, name: `方案${id}`, phase: 'REQUIREMENTS_DRAFT', revision: 0, createdAt: '', updatedAt: '' })
  vi.spyOn(solutionsApi, 'list').mockReturnValueOnce(delayedOld).mockResolvedValueOnce([entry('B', 'space-2')])
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/spaces/:spaceId/solutions', component: SolutionListView }] })
  await router.push('/spaces/space-1/solutions'); await router.isReady()
  const wrapper = mount(SolutionListView, { global: { plugins: [router] } })
  await flushPromises()
  await router.push('/spaces/space-2/solutions')
  await flushPromises()
  finishOld([entry('A', 'space-1')])
  await flushPromises()
  expect(wrapper.text()).toContain('方案B')
  expect(wrapper.text()).not.toContain('方案A')
  wrapper.unmount()
})

it('直接进入后续页的空间时保留目标，不会切换到首个空间', async () => {
  const first = Array.from({ length: 100 }, (_, i) => ({ ...space, id: `space-${i}`, name: `空间${i}` }))
  vi.spyOn(spacesApi, 'listSpaces').mockResolvedValueOnce(first).mockResolvedValueOnce([{ ...space, id: 'target', name: '目标空间' }])
  const list = vi.spyOn(solutionsApi, 'list').mockResolvedValue([])
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/spaces/:spaceId/solutions', component: SolutionListView }] })
  await router.push('/spaces/target/solutions'); await router.isReady()
  const wrapper = mount(SolutionListView, { global: { plugins: [router] } })
  await flushPromises()
  expect(router.currentRoute.value.params.spaceId).toBe('target')
  expect((wrapper.get('[name="solution-space"]').element as HTMLSelectElement).value).toBe('target')
  expect(list).toHaveBeenCalledWith('target', 1, 20)
  wrapper.unmount()
})

it('目标空间不存在或无权限时不跳转到其他空间', async () => {
  vi.spyOn(spacesApi, 'listSpaces').mockResolvedValue([space])
  const list = vi.spyOn(solutionsApi, 'list').mockResolvedValue([])
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/spaces/:spaceId/solutions', component: SolutionListView }] })
  await router.push('/spaces/unknown/solutions'); await router.isReady()
  const wrapper = mount(SolutionListView, { global: { plugins: [router] } })
  await flushPromises()
  expect(router.currentRoute.value.params.spaceId).toBe('unknown')
  expect(wrapper.get('[role="alert"]').text()).toContain('空间不存在或无权访问')
  expect(list).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('从已有空间进入不存在的空间时，不保留原空间的创建入口', async () => {
  vi.spyOn(spacesApi, 'listSpaces').mockResolvedValue([space])
  vi.spyOn(solutionsApi, 'list').mockResolvedValue([])
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/spaces/:spaceId/solutions', component: SolutionListView }] })
  await router.push('/spaces/space-1/solutions'); await router.isReady()
  const wrapper = mount(SolutionListView, { global: { plugins: [router] } })
  await flushPromises()
  expect(wrapper.find('[data-form="create-solution"]').exists()).toBe(true)
  await router.push('/spaces/unknown/solutions'); await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('空间不存在或无权访问')
  expect(wrapper.find('[data-form="create-solution"]').exists()).toBe(false)
  wrapper.unmount()
})

it('切换目标时空间列表加载失败，不展示原空间的创建入口', async () => {
  vi.spyOn(spacesApi, 'listSpaces').mockResolvedValueOnce([space]).mockRejectedValueOnce(new Error('网络异常'))
  vi.spyOn(solutionsApi, 'list').mockResolvedValue([])
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/spaces/:spaceId/solutions', component: SolutionListView }] })
  await router.push('/spaces/space-1/solutions'); await router.isReady()
  const wrapper = mount(SolutionListView, { global: { plugins: [router] } })
  await flushPromises()
  await router.push('/spaces/unknown/solutions'); await flushPromises()
  expect(wrapper.find('[data-form="create-solution"]').exists()).toBe(false)
  expect(wrapper.get('[role="alert"]').text()).toContain('加载知识空间失败')
  wrapper.unmount()
})

it('创建失败后相同输入重试复用请求标识，修改输入后使用新标识', async () => {
  vi.spyOn(spacesApi, 'listSpaces').mockResolvedValue([space])
  vi.spyOn(solutionsApi, 'list').mockResolvedValue([])
  const create = vi.spyOn(solutionsApi, 'create').mockRejectedValue(new Error('网络中断'))
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/spaces/:spaceId/solutions', component: SolutionListView }] })
  await router.push('/spaces/space-1/solutions'); await router.isReady()
  const wrapper = mount(SolutionListView, { global: { plugins: [router] } })
  await flushPromises()
  await wrapper.get('[name="solution-name"]').setValue('原始方案')
  await wrapper.get('[name="initial-requirement"]').setValue('原始需求')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  await wrapper.get('form').trigger('submit'); await flushPromises()
  await wrapper.get('[name="solution-name"]').setValue('修改方案')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(create.mock.calls[0]?.[4]).toBe(create.mock.calls[1]?.[4])
  expect(create.mock.calls[2]?.[4]).not.toBe(create.mock.calls[1]?.[4])
  wrapper.unmount()
})

it('切换空间时隔离新方案草稿，切回后保留原输入', async () => {
  vi.spyOn(spacesApi, 'listSpaces').mockResolvedValue([space, { ...space, id: 'space-2', name: '另一空间' }])
  vi.spyOn(solutionsApi, 'list').mockResolvedValue([])
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/spaces/:spaceId/solutions', component: SolutionListView }] })
  await router.push('/spaces/space-1/solutions'); await router.isReady()
  const wrapper = mount(SolutionListView, { global: { plugins: [router] } })
  await flushPromises()
  await wrapper.get('[name="solution-name"]').setValue('甲空间草稿')
  await wrapper.get('[name="initial-requirement"]').setValue('甲空间需求')
  await wrapper.get('[name="solution-space"]').setValue('space-2'); await flushPromises()
  expect((wrapper.get('[name="solution-name"]').element as HTMLInputElement).value).toBe('')
  await wrapper.get('[name="solution-name"]').setValue('乙空间草稿')
  await wrapper.get('[name="solution-space"]').setValue('space-1'); await flushPromises()
  expect((wrapper.get('[name="solution-name"]').element as HTMLInputElement).value).toBe('甲空间草稿')
  expect((wrapper.get('[name="initial-requirement"]').element as HTMLTextAreaElement).value).toBe('甲空间需求')
  wrapper.unmount()
})
