import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'

import SolutionSectionGenerationPanel from './SolutionSectionGenerationPanel.vue'

const section = { sectionId: 'section-1', title: '总体方案', level: 1, order: 1,
  required: true, objective: '说明实施方法', retrievalHint: '查找依据', riskTypes: [] }
const draft = { id: 'draft-1', content: '## 总体方案\n支持100名用户', evidenceIds: ['evidence-1'],
  claims: [{ claimId: 'claim-1', text: '支持100名用户', factual: true,
    evidenceIds: ['evidence-1'], riskTypes: [] }],
  verification: { verifications: [{ claimId: 'claim-1', verdict: 'HUMAN_CONFIRMATION_REQUIRED',
    reason: 'NEEDS_REVIEW' }] } }
const succeeded = { id: 'attempt-1', status: 'SUCCEEDED', executionCount: 1,
  revision: 4, failureCode: null, sectionVersion: draft }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
})

beforeEach(() => { vi.restoreAllMocks(); sessionStorage.clear() })

it('生成后展示核验结论，要求人工核对后才能采纳，并发送当前基线', async () => {
  const posts: { path: string, body: Record<string, unknown>, key: string }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    if (path.endsWith('/session/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf', parameterName: '_csrf' })
    if (init?.method === 'POST') {
      posts.push({ path, body: JSON.parse(String(init.body)) as Record<string, unknown>,
        key: new Headers(init.headers).get('Idempotency-Key') ?? '' })
      return path.endsWith('/adopt') ? json({ solutionId: 'solution-1', revision: 8 }) : json(succeeded)
    }
    throw new Error(path)
  }))
  const wrapper = mount(SolutionSectionGenerationPanel, { props: {
    solutionId: 'solution-1', section, revision: 7, baselineVersionId: 'adopted-1',
    canGenerate: true, disabled: false,
  } })
  await wrapper.get('[data-action="generate-section"]').trigger('click')
  await flushPromises()
  expect(posts[0]?.body).toEqual({ expectedRevision: 7, baselineSectionVersionId: 'adopted-1' })
  expect(posts[0]?.key).toBeTruthy()
  expect(wrapper.text()).toContain('需人工确认')
  expect(wrapper.text()).toContain('支持100名用户')
  expect(wrapper.get('[data-action="adopt-section"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[data-action="review-section"]').setValue(true)
  await wrapper.get('[data-action="adopt-section"]').trigger('click')
  await flushPromises()
  expect(posts[1]?.body).toEqual({ expectedRevision: 7 })
  expect(wrapper.emitted('adopted')).toHaveLength(1)
  wrapper.unmount()
})

it('没有服务端权限时隐藏操作；409 冲突提供重新加载入口', async () => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    if (path.endsWith('/session/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf', parameterName: '_csrf' })
    if (init?.method === 'POST') return json({ code: 'SOLUTION_CONCURRENT_UPDATE',
      message: '方案已由他人修改', traceId: 'trace', fieldErrors: {} }, 409)
    throw new Error(path)
  }))
  const wrapper = mount(SolutionSectionGenerationPanel, { props: {
    solutionId: 'solution-1', section, revision: 7, baselineVersionId: null,
    canGenerate: false, disabled: false,
  } })
  expect(wrapper.find('[data-action="generate-section"]').exists()).toBe(false)
  await wrapper.setProps({ canGenerate: true })
  await wrapper.get('[data-action="generate-section"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('方案已由他人修改')
  expect(wrapper.find('[data-action="reload-generation-conflict"]').exists()).toBe(true)
  wrapper.unmount()
})

it('页面恢复可重试状态并沿用原客户端键；阻断结论禁止采纳', async () => {
  sessionStorage.setItem('solution-generation:solution-1:section-1', 'existing-key')
  const sentKeys: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    if (path.endsWith('/session/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf', parameterName: '_csrf' })
    if (path.includes('clientKey=existing-key')) return json({ ...succeeded,
      status: 'FAILED_RETRYABLE', failureCode: 'MODEL_UNAVAILABLE', sectionVersion: null })
    if (init?.method === 'POST') {
      sentKeys.push(new Headers(init.headers).get('Idempotency-Key') ?? '')
      return json({ ...succeeded, sectionVersion: { ...draft,
        verification: { verifications: [{ claimId: 'claim-1', verdict: 'UNSUPPORTED', reason: 'NO_MATCH' }] } } })
    }
    throw new Error(path)
  }))
  const wrapper = mount(SolutionSectionGenerationPanel, { props: {
    solutionId: 'solution-1', section, revision: 7, baselineVersionId: null,
    canGenerate: true, disabled: false,
  } })
  await flushPromises()
  expect(wrapper.text()).toContain('暂时失败，可重试')
  await wrapper.get('[data-action="generate-section"]').trigger('click')
  await flushPromises()
  expect(sentKeys).toEqual(['existing-key'])
  expect(wrapper.text()).toContain('不支持，禁止采纳')
  expect(wrapper.find('[data-action="adopt-section"]').exists()).toBe(false)
  wrapper.unmount()
})

it.each([404, 422])('采纳返回 %i 时会立即清除已展示的草稿', async (status) => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    if (path.endsWith('/session/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf', parameterName: '_csrf' })
    if (init?.method === 'POST' && path.endsWith('/adopt')) return json({
      code: status === 404 ? 'NOT_FOUND' : 'SECTION_CANDIDATE_STALE',
      message: status === 404 ? '知识资源不存在' : '章节候选版本已过期',
      traceId: 'trace', fieldErrors: {},
    }, status)
    if (init?.method === 'POST') return json(succeeded)
    throw new Error(path)
  }))
  const wrapper = mount(SolutionSectionGenerationPanel, { props: {
    solutionId: 'solution-1', section, revision: 7, baselineVersionId: null,
    canGenerate: true, disabled: false,
  } })
  await wrapper.get('[data-action="generate-section"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('支持100名用户')
  await wrapper.get('[data-action="review-section"]').setValue(true)
  await wrapper.get('[data-action="adopt-section"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('支持100名用户')
  expect(sessionStorage.getItem('solution-generation:solution-1:section-1')).toBeNull()
  if (status === 422) expect(wrapper.find('[data-action="reload-generation-conflict"]').exists()).toBe(true)
  wrapper.unmount()
})

it('新生成请求来源失效时不会继续显示旧草稿', async () => {
  let generationCalls = 0
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    if (path.endsWith('/session/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf', parameterName: '_csrf' })
    if (init?.method === 'POST') {
      generationCalls += 1
      return generationCalls === 1 ? json(succeeded) : json({
        code: 'NOT_FOUND', message: '来源已失效', traceId: 'trace', fieldErrors: {},
      }, 404)
    }
    throw new Error(path)
  }))
  const wrapper = mount(SolutionSectionGenerationPanel, { props: {
    solutionId: 'solution-1', section, revision: 7, baselineVersionId: null,
    canGenerate: true, disabled: false,
  } })
  await wrapper.get('[data-action="generate-section"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('支持100名用户')
  await wrapper.get('[data-action="generate-section"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('支持100名用户')
  expect(sessionStorage.getItem('solution-generation:solution-1:section-1')).toBeNull()
  wrapper.unmount()
})
