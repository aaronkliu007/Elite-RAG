import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import SolutionListView from './SolutionListView.vue'

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
