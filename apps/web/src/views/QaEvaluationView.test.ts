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
})
