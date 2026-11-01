import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import QaEvaluationView from './QaEvaluationView.vue'

describe('问答评测结果', () => {
  it('显示逐案例拒答原因、审核计数和当前门禁', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      runId: 'run-1', datasetVersion: 'v1', assessment: { passed: false, failures: ['CLAIM_REVIEW_COVERAGE'] },
      historicalAssessment: { passed: true, failures: [] },
      caseResults: [{ caseId: 'case-1', requestId: 'request-1', expectedRefusal: false, status: 'REFUSED',
        reason: 'CONFLICTING_EVIDENCE', expectedEvidence: 2, retrievedEvidence: 1,
        generatedClaims: 0, reviewedClaims: 0, supportedClaims: 0, leakageCount: 0 }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    const wrapper = mount(QaEvaluationView)
    await wrapper.get('input[name="runId"]').setValue('run-1')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('当前门禁未通过')
    expect(wrapper.text()).toContain('证据冲突')
    expect(wrapper.text()).toContain('case-1')
    expect(wrapper.text()).toContain('历史判断')
    expect(wrapper.text()).not.toContain('答案正文')
    await wrapper.get('select[name="filter"]').setValue('review')
    expect(wrapper.text()).not.toContain('case-1')
    expect(wrapper.text()).toContain('没有符合筛选条件的案例')
    await wrapper.get('select[name="filter"]').setValue('unexpected-refusal')
    expect(wrapper.text()).toContain('case-1')
  })

  it('加载受权审核上下文，生成需人工维护的标注片段', async () => {
    const source = { documentId: 'document-1', versionId: 'version-1', nodeId: 'node',
      contentDigest: 'a'.repeat(64), titleDigest: 'b'.repeat(64), locatorDigest: 'c'.repeat(64) }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const data = String(input).endsWith('review-context')
        ? { caseId: 'case-1', requestId: 'request-1', question: '虚构问题', reviewQuestionDigest: 'd'.repeat(64),
          claims: [{ claimId: 'claim-1', text: '虚构声明', evidenceIds: ['evidence-1'], supportingEvidence: [source], eligible: true }] }
        : { runId: 'run-1', datasetVersion: 'v1', assessment: { passed: false, failures: [] },
          historicalAssessment: { passed: false, failures: [] }, caseResults: [{ caseId: 'case-1', requestId: 'request-1',
            expectedRefusal: false, status: 'ANSWERED', reason: null, expectedEvidence: 1, retrievedEvidence: 1,
            generatedClaims: 1, reviewedClaims: 0, supportedClaims: 0, leakageCount: 0 }] }
      return new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const wrapper = mount(QaEvaluationView)
    await wrapper.get('input[name="runId"]').setValue('run-1')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('[data-action="load-review"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('虚构问题')
    expect(wrapper.text()).toContain('虚构声明')
    expect((wrapper.get('select[name="verdict"]').element as HTMLSelectElement).value).toBe('')
    expect((wrapper.get('select[name="verdict"]').element as HTMLSelectElement).selectedIndex).toBe(0)
    await wrapper.get('input[name="reviewReference"]').setValue('审核记录-001')
    await wrapper.get('select[name="verdict"]').setValue('SUPPORTED')
    await wrapper.get('[data-action="prepare-label"]').trigger('click')
    await flushPromises()
    const label = JSON.parse((wrapper.get('textarea[name="reviewDraft"]').element as HTMLTextAreaElement).value)
    expect(label.reviewQuestionDigest).toBe('d'.repeat(64))
    expect(label.reviewedClaims[0]).toMatchObject({ text: '虚构声明', verdict: 'SUPPORTED', reviewReference: '审核记录-001' })
  })

  it('审核期间撤权后不生成片段，并清除已展示的声明', async () => {
    let reviewRequests = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith('review-context')) {
        reviewRequests += 1
        if (reviewRequests > 1) return new Response('{}', { status: 404, headers: { 'Content-Type': 'application/json' } })
        return new Response(JSON.stringify({ caseId: 'case-1', requestId: 'request-1', question: '虚构问题',
          reviewQuestionDigest: 'd'.repeat(64), claims: [{ claimId: 'claim-1', text: '虚构声明',
            evidenceIds: ['evidence-1'], supportingEvidence: [{ documentId: 'doc', versionId: 'v', nodeId: 'n',
              contentDigest: 'a'.repeat(64), titleDigest: 'b'.repeat(64), locatorDigest: 'c'.repeat(64) }], eligible: true }] }),
        { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      return new Response(JSON.stringify({ runId: 'run-1', datasetVersion: 'v1', assessment: { passed: false, failures: [] },
        historicalAssessment: { passed: false, failures: [] }, caseResults: [{ caseId: 'case-1', requestId: 'request-1',
          expectedRefusal: false, status: 'ANSWERED', reason: null, expectedEvidence: 1, retrievedEvidence: 1,
          generatedClaims: 1, reviewedClaims: 0, supportedClaims: 0, leakageCount: 0 }] }),
      { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const wrapper = mount(QaEvaluationView)
    await wrapper.get('input[name="runId"]').setValue('run-1')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('[data-action="load-review"]').trigger('click')
    await flushPromises()
    await wrapper.get('input[name="reviewReference"]').setValue('审核记录-001')
    await wrapper.get('select[name="verdict"]').setValue('SUPPORTED')
    await wrapper.get('[data-action="prepare-label"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('textarea[name="reviewDraft"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('虚构声明')
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
  })
})
