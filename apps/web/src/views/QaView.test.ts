import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import { clearCsrfToken } from '../api/http'
import QaView from './QaView.vue'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const answered = {
  requestId: '10000000-0000-7000-8000-000000000001', status: 'ANSWERED',
  answer: '制度自 2026 年 10 月起生效。', refusal: null, degraded: true,
  citations: [{ claimId: '20000000-0000-7000-8000-000000000001', text: '制度自 2026 年 10 月起生效', evidenceIds: ['30000000-0000-7000-8000-000000000001'] }],
  completedAt: '2026-10-27T06:00:00Z',
}

async function mountAt(path = '/qa') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/qa', component: QaView }] })
  await router.push(path)
  await router.isReady()
  return { wrapper: mount(QaView, { global: { plugins: [router] } }), router }
}

describe('知识问答页', () => {
  beforeEach(() => { clearCsrfToken(); vi.restoreAllMocks() })

  it('每个新问题只生成一个幂等键，并在提交期间阻止重复请求', async () => {
    let resolveQuestion: ((response: Response) => void) | undefined
    const questionResponse = new Promise<Response>((resolve) => { resolveQuestion = resolve })
    const calls: Array<{ path: string, init?: RequestInit }> = []
    let questionCalls = 0
    vi.spyOn(crypto, 'randomUUID')
      .mockReturnValueOnce('00000000-0000-4000-8000-000000000001')
      .mockReturnValueOnce('00000000-0000-4000-8000-000000000002')
      .mockReturnValue('00000000-0000-4000-8000-000000000099')
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/session/csrf') return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-qa' })
      calls.push({ path, init })
      questionCalls += 1
      return questionCalls === 1 ? questionResponse : json({ ...answered, requestId: 'request-2' })
    }))
    const { wrapper } = await mountAt()
    await wrapper.get('textarea[name="question"]').setValue('制度何时生效？')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(calls).toHaveLength(1)
    expect(new Headers(calls[0]?.init?.headers).get('Idempotency-Key')).toBe('00000000-0000-4000-8000-000000000001')
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual({ question: '制度何时生效？' })
    expect(wrapper.get('[data-action="ask"]').attributes('disabled')).toBeDefined()

    resolveQuestion?.(json(answered))
    await flushPromises()
    expect(wrapper.text()).toContain(answered.answer)
    expect(wrapper.get('[data-state="degraded"]').text()).toContain('回答已降级')
    expect(wrapper.text()).toContain(answered.citations[0].text)

    await wrapper.get('textarea[name="question"]').setValue('另一个新问题')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(calls).toHaveLength(2)
    expect(new Headers(calls[1]?.init?.headers).get('Idempotency-Key')).not.toBe(new Headers(calls[0]?.init?.headers).get('Idempotency-Key'))
  })

  it('按 requestId 恢复结果，并把严格拒答与系统故障明确分开', async () => {
    const responses = [
      { ...answered, requestId: 'request-refused', status: 'REFUSED', answer: null, refusal: 'INSUFFICIENT_EVIDENCE', degraded: false, citations: [] },
      { ...answered, requestId: 'request-failed', status: 'FAILED', answer: null, refusal: null, degraded: false, citations: [] },
    ]
    let index = 0
    vi.stubGlobal('fetch', vi.fn(async () => json(responses[index++])))
    const first = await mountAt('/qa?requestId=request-refused')
    await flushPromises()
    expect(first.wrapper.get('[data-state="refused"]').text()).toContain('依据不足，系统已严格拒答')
    expect(first.wrapper.text()).not.toContain('系统处理失败')
    first.wrapper.unmount()

    const second = await mountAt('/qa?requestId=request-failed')
    await flushPromises()
    expect(second.wrapper.get('[data-state="failed"]').text()).toContain('系统处理失败')
    expect(second.wrapper.get('[data-state="failed"]').text()).not.toContain('严格拒答')
  })

  it('提供可访问的初始空状态与恢复加载状态', async () => {
    let resolveRestore: ((response: Response) => void) | undefined
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { resolveRestore = resolve })))
    const empty = await mountAt()
    expect(empty.wrapper.get('[data-state="empty"]').attributes('aria-live')).toBe('polite')
    empty.wrapper.unmount()

    const restoring = await mountAt('/qa?requestId=request-loading')
    expect(restoring.wrapper.get('[data-state="loading"]').attributes('role')).toBe('status')
    resolveRestore?.(json(answered))
    await flushPromises()
  })
})
