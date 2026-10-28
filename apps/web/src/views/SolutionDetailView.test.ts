import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import SolutionDetailView from './SolutionDetailView.vue'

const detail = {
  id: 'solution-1', spaceId: 'space-1', name: '测试方案', phase: 'REQUIREMENTS_DRAFT',
  revision: 0, solutionRevision: 1, createdAt: '2026-10-28T10:00:00Z',
  requirementVersion: { id: 'requirement-1', number: 1, createdBy: 'user-1',
    createdAt: '2026-10-28T10:00:00Z', changeReason: '初始录入',
    items: [{ itemId: 'item-1', category: 'UNKNOWN', text: '待确认', sources: [] }] },
  outlineVersion: null, requirementConfirmation: null, outlineConfirmation: null,
  sourceAccessValid: true, allowedActions: ['EDIT_REQUIREMENTS'],
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
})

beforeEach(() => vi.restoreAllMocks())

it('无确认权限时隐藏确认按钮，保存失败保留输入', async () => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input)
    if (path.endsWith('/session/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf', parameterName: '_csrf' })
    if (path.endsWith('/solutions/solution-1') && !path.includes('/requirements')) return json(detail)
    if (path.endsWith('/solutions/solution-1/requirements')) return json({
      code: 'SERVICE_UNAVAILABLE', message: '暂时不可用', traceId: 'trace', fieldErrors: {},
    }, 503)
    throw new Error(path)
  }))
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/solutions/:solutionId', component: SolutionDetailView },
    { path: '/spaces/:spaceId/solutions', component: { template: '<div />' } },
  ] })
  await router.push('/solutions/solution-1')
  await router.isReady()
  const wrapper = mount(SolutionDetailView, { global: { plugins: [router] } })
  await flushPromises()
  expect(wrapper.find('[data-action="confirm-requirements"]').exists()).toBe(false)
  await wrapper.get('textarea[name="requirement-text-0"]').setValue('修改后的正文')
  await wrapper.get('input[name="change-reason"]').setValue('补充目标')
  await wrapper.get('[data-action="save-requirements"]').trigger('click')
  await flushPromises()
  expect((wrapper.get('textarea[name="requirement-text-0"]').element as HTMLTextAreaElement).value)
    .toBe('修改后的正文')
  expect(wrapper.get('[role="alert"]').text()).toContain('暂时不可用')
})
