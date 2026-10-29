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

beforeEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('风险确认后才允许定稿，定稿后展示 Word 下载', async () => {
  let confirmed = false
  let finalized = false
  let revision = 4
  const requests: string[] = []
  const feedbackKeys: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    if (path.endsWith('/session/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf', parameterName: '_csrf' })
    if (path.endsWith('/finalization-review')) return json({ solutionId: 'solution-1', revision,
      risks: [{ sectionId: 'section-1', sectionTitle: '总体设计', sectionVersionId: 'adopted-1',
        claimId: 'claim-1', claimText: '性能达到目标', riskTypes: ['PERFORMANCE_OR_SLA'],
        confirmed, confirmedBy: confirmed ? 'reviewer-1' : null, confirmedAt: confirmed ? '2026-10-29T00:00:00Z' : null }] })
    if (path.endsWith('/risk-confirmations')) {
      requests.push(`risk:${JSON.parse(String(init?.body)).reason}`)
      confirmed = true
      return json({ id: 'confirmation-1' })
    }
    if (path.endsWith('/finalize')) {
      requests.push(`finalize:${JSON.parse(String(init?.body)).expectedRevision}`)
      finalized = true
      revision += 1
      return json({ id: 'final-1', solutionRevision: 1, contentHash: 'a'.repeat(64),
        createdAt: '2026-10-29T00:00:00Z' })
    }
    if (path.endsWith('/feedback/mine')) return json([])
    if (path.endsWith('/feedback') && init?.method === 'POST') {
      const body = JSON.parse(String(init.body))
      requests.push(`feedback:${body.reason}:${body.sectionVersionId}`)
      feedbackKeys.push(new Headers(init.headers).get('Idempotency-Key') ?? '')
      if (feedbackKeys.length <= 3) return json({ code: 'SERVICE_UNAVAILABLE', message: '暂时不可用' }, 503)
      return json({ id: 'feedback-1', finalizedVersionId: 'final-1',
        sectionVersionId: body.sectionVersionId, reason: body.reason, createdAt: '2026-10-29T00:00:00Z' }, 201)
    }
    if (path.endsWith('/solutions/solution-1')) return json({ ...detail,
      phase: finalized ? 'FINALIZED' : 'IN_REVIEW', revision,
      outlineVersion: { id: 'outline-1', number: 1, nodes: [{ sectionId: 'section-1', title: '总体设计',
        level: 1, order: 1, required: true, objective: '设计', retrievalHint: '检索', riskTypes: [] }],
        createdBy: 'user-1', createdAt: '2026-10-28T10:00:00Z', changeReason: '初版' },
      outlineConfirmation: { versionId: 'outline-1', confirmedBy: 'reviewer-1',
        confirmedAt: '2026-10-28T10:00:00Z', valid: true },
      requirementConfirmation: { versionId: 'requirement-1', confirmedBy: 'reviewer-1',
        confirmedAt: '2026-10-28T10:00:00Z', valid: true },
      adoptedSectionVersionIds: { 'section-1': 'adopted-1' },
      finalizedVersionId: finalized ? 'final-1' : null,
      allowedActions: finalized ? [] : ['CONFIRM_RISK', 'FINALIZE'] })
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
  expect(wrapper.get('[data-action="finalize-solution"]').element).toHaveProperty('disabled', true)
  await wrapper.get('input[name="risk-reason-claim-1"]').setValue('已核实测试报告')
  await wrapper.findAll('button').find((button) => button.text() === '确认此项风险')!.trigger('click')
  await flushPromises()
  expect(requests).toEqual(['risk:已核实测试报告'])
  expect(wrapper.get('[data-action="finalize-solution"]').element).toHaveProperty('disabled', false)
  await wrapper.get('[data-action="finalize-solution"]').trigger('click')
  await flushPromises()
  expect(requests).toEqual(['risk:已核实测试报告', 'finalize:4'])
  expect(wrapper.get('[data-action="download-word"]').text()).toContain('下载 Word')
  await wrapper.get('[data-action="feedback-reason"]').setValue('MISSING_EVIDENCE')
  await wrapper.get('[data-action="feedback-section"]').setValue('adopted-1')
  await wrapper.get('[data-action="submit-feedback"]').trigger('click')
  await flushPromises()
  expect(requests).toContain('feedback:MISSING_EVIDENCE:adopted-1')
  await wrapper.get('[data-action="submit-feedback"]').trigger('click')
  await flushPromises()
  expect(feedbackKeys[1]).toBe(feedbackKeys[0])
  await wrapper.get('[data-action="feedback-reason"]').setValue('FACT_ERROR')
  await wrapper.get('[data-action="submit-feedback"]').trigger('click')
  await flushPromises()
  expect(feedbackKeys[2]).not.toBe(feedbackKeys[0])
  expect(requests).toContain('feedback:FACT_ERROR:adopted-1')
  await wrapper.get('[data-action="feedback-section"]').setValue('')
  await wrapper.get('[data-action="submit-feedback"]').trigger('click')
  await flushPromises()
  expect(feedbackKeys[3]).not.toBe(feedbackKeys[2])
  expect(requests).toContain('feedback:FACT_ERROR:null')
  expect(wrapper.get('[aria-label="我的反馈记录"]').text()).toContain('事实错误')
  wrapper.unmount()
})

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
  const leaveWithDraft = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(leaveWithDraft)
  expect(leaveWithDraft.defaultPrevented).toBe(true)
  await wrapper.get('[data-action="save-requirements"]').trigger('click')
  await flushPromises()
  expect((wrapper.get('textarea[name="requirement-text-0"]').element as HTMLTextAreaElement).value)
    .toBe('修改后的正文')
  expect(wrapper.get('[role="alert"]').text()).toContain('暂时不可用')
  wrapper.unmount()
  const leaveAfterUnmount = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(leaveAfterUnmount)
  expect(leaveAfterUnmount.defaultPrevented).toBe(false)
})

it('保存请求未返回时锁定编辑器，避免刷新覆盖新输入', async () => {
  let releaseSave: ((response: Response) => void) | undefined
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
    const path = String(input)
    if (path.endsWith('/session/csrf')) return Promise.resolve(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf', parameterName: '_csrf' }))
    if (path.endsWith('/solutions/solution-1') && !path.includes('/requirements')) return Promise.resolve(json(detail))
    if (path.endsWith('/solutions/solution-1/requirements')) return new Promise<Response>((resolve) => { releaseSave = resolve })
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
  await wrapper.get('textarea[name="requirement-text-0"]').setValue('发送时的正文')
  await wrapper.get('input[name="change-reason"]').setValue('修订目标')
  await wrapper.get('[data-action="save-requirements"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('textarea[name="requirement-text-0"]').element.closest('section')?.hasAttribute('inert')).toBe(true)
  expect(wrapper.get('input[name="change-reason"]').element.closest('section')?.hasAttribute('inert')).toBe(true)
  expect(releaseSave).toBeTypeOf('function')
  releaseSave!(json({ solutionId: 'solution-1', revision: 1, requirementVersionId: 'requirement-2',
    outlineVersionId: null, phase: 'REQUIREMENTS_DRAFT' }))
  await vi.waitFor(() => expect(wrapper.get('textarea[name="requirement-text-0"]').element.closest('section')
    ?.hasAttribute('inert')).toBe(false))
  wrapper.unmount()
})
