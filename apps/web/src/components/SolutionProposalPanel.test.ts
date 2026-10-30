import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import SolutionProposalPanel from './SolutionProposalPanel.vue'
import { solutionsApi } from '../api/solutions'
import { ApiRequestError } from '../api/http'

it('需求整理候选允许审阅者根据来源定位打开文档', async () => {
  sessionStorage.clear()
  vi.spyOn(solutionsApi, 'generateProposal').mockResolvedValue({ id: 'candidate-1', kind: 'REQUIREMENTS',
    status: 'SUCCEEDED', requirementVersionId: 'req-1', expectedRevision: 0, failureCode: null,
    result: { kind: 'REQUIREMENTS', items: [{ category: 'GOAL', text: '客户目标', sources: [{ documentId: 'doc-1',
      versionId: 'version-1', locator: { type: 'TABLE', tableName: '建设清单', startRow: 2, endRow: 5,
        startColumn: 1, endColumn: 3 } }] }] } } as never)
  const wrapper = mount(SolutionProposalPanel, { props: { actorId: 'user-1', solutionId: 'solution-1', kind: 'requirements',
    revision: 0, currentTexts: [], disabled: false } })
  await wrapper.get('[data-action="generate-proposal"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('建设清单 · 第 2–5 行 · 第 1–3 列')
  expect(wrapper.text()).toContain('打开的是文档当前版本')
  expect(wrapper.get('a[href="/documents/doc-1"]').attributes('target')).toBe('_blank')
  wrapper.unmount(); sessionStorage.clear(); vi.restoreAllMocks()
})

it('候选经人工审阅才可采纳，版本冲突保留候选', async () => {
  const candidate = { id: 'candidate-1', kind: 'REQUIREMENTS', status: 'SUCCEEDED',
    requirementVersionId: 'req-1', expectedRevision: 0, failureCode: null,
    result: { kind: 'REQUIREMENTS', items: [{ category: 'UNKNOWN', text: '整理后的需求', sources: [] }] } } as const
  vi.spyOn(solutionsApi, 'generateProposal').mockResolvedValue(candidate as never)
  const save = vi.spyOn(solutionsApi, 'reviseRequirements').mockRejectedValue(
    new ApiRequestError('版本已变化', 409, 'SOLUTION_CONCURRENT_UPDATE', 'trace', {}))
  const wrapper = mount(SolutionProposalPanel, { props: { actorId: 'user-1', solutionId: 'solution-1', kind: 'requirements',
    revision: 0, currentTexts: ['原始需求'], disabled: false } })
  await wrapper.get('[data-action="generate-proposal"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('整理后的需求')
  expect(save).not.toHaveBeenCalled()
  expect(wrapper.get('[data-action="adopt-proposal"]').element).toHaveProperty('disabled', true)
  await wrapper.get('input[type="checkbox"]').setValue(true)
  await wrapper.get('[data-action="adopt-proposal"]').trigger('click')
  await flushPromises()
  expect(save).toHaveBeenCalledWith('solution-1', candidate.result.items, '采纳模型需求整理候选', 0, expect.any(String))
  expect(wrapper.emitted('adopted')).toBeUndefined()
  expect(wrapper.text()).toContain('整理后的需求')
  expect(wrapper.find('[data-action="discard-proposal"]').exists()).toBe(false)
  wrapper.unmount()
  vi.restoreAllMocks()
  sessionStorage.clear()
})

it('未到达服务端的请求可以用原键重发，其他用户不继承请求键', async () => {
  sessionStorage.clear()
  const generate = vi.spyOn(solutionsApi, 'generateProposal').mockRejectedValue(new Error('offline'))
  const query = vi.spyOn(solutionsApi, 'getProposal').mockRejectedValue(
    new ApiRequestError('未找到', 404, 'NOT_FOUND', '', {}))
  const props = { actorId: 'user-1', solutionId: 'solution-1', kind: 'requirements' as const,
    revision: 0, currentTexts: [], disabled: false }
  const wrapper = mount(SolutionProposalPanel, { props })
  await wrapper.get('[data-action="generate-proposal"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-action="generate-proposal"]').trigger('click')
  await flushPromises()
  expect(generate).toHaveBeenCalledTimes(2)
  expect(generate.mock.calls[0]?.[3]).toBe(generate.mock.calls[1]?.[3])
  wrapper.unmount()
  const other = mount(SolutionProposalPanel, { props: { ...props, actorId: 'user-2' } })
  await flushPromises()
  expect(query).not.toHaveBeenCalled()
  other.unmount()
  sessionStorage.clear()
  vi.restoreAllMocks()
})

it('大纲候选展示检索提示和风险，采纳后创建待确认修订', async () => {
  sessionStorage.clear()
  const nodes = [{ title: '建设方案', level: 1, order: 1, required: true,
    objective: '明确技术路线', retrievalHint: '检索企业技术规范', riskTypes: ['PERFORMANCE_OR_SLA'] }]
  vi.spyOn(solutionsApi, 'generateProposal').mockResolvedValue({ id: 'outline-proposal', kind: 'OUTLINE',
    status: 'SUCCEEDED', requirementVersionId: 'req-1', expectedRevision: 1, failureCode: null,
    result: { kind: 'OUTLINE', nodes } } as never)
  const save = vi.spyOn(solutionsApi, 'reviseOutline').mockResolvedValue({} as never)
  const wrapper = mount(SolutionProposalPanel, { props: { actorId: 'user-1', solutionId: 'solution-1',
    kind: 'outline', revision: 1, currentTexts: [], disabled: false } })
  await wrapper.get('[data-action="generate-proposal"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('检索企业技术规范')
  expect(wrapper.text()).toContain('性能或服务级别')
  await wrapper.get('input[type="checkbox"]').setValue(true)
  await wrapper.get('[data-action="adopt-proposal"]').trigger('click')
  await flushPromises()
  expect(save).toHaveBeenCalledWith('solution-1', nodes, '采纳模型大纲候选', 1, expect.any(String))
  expect(wrapper.emitted('adopted')).toHaveLength(1)
  wrapper.unmount()
  sessionStorage.clear()
  vi.restoreAllMocks()
})

it.each(['FAILED', 'SUCCEEDED'] as const)('明确%s的请求可放弃候选并以新键重新生成', async status => {
  sessionStorage.clear()
  const attempt = { id: 'candidate', kind: 'REQUIREMENTS', status, requirementVersionId: 'req-1',
    expectedRevision: 0, failureCode: status === 'FAILED' ? 'MODEL_OUTPUT_INVALID' : null,
    result: status === 'SUCCEEDED' ? { kind: 'REQUIREMENTS', items: [{ category: 'UNKNOWN', text: '候选', sources: [] }] } : null }
  const generate = vi.spyOn(solutionsApi, 'generateProposal').mockResolvedValue(attempt as never)
  const wrapper = mount(SolutionProposalPanel, { props: { actorId: 'user-1', solutionId: 'solution-1',
    kind: 'requirements', revision: 0, currentTexts: [], disabled: false } })
  await wrapper.get('[data-action="generate-proposal"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-action="discard-proposal"]').trigger('click')
  expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)
  expect(generate).toHaveBeenCalledTimes(1)
  await wrapper.get('[data-action="generate-proposal"]').trigger('click')
  await flushPromises()
  expect(generate).toHaveBeenCalledTimes(2)
  expect(generate.mock.calls[0]?.[3]).not.toBe(generate.mock.calls[1]?.[3])
  wrapper.unmount()
  sessionStorage.clear()
  vi.restoreAllMocks()
})

it.each(['RUNNING', 'INDETERMINATE'] as const)('%s请求不能丢弃并发起新调用', async status => {
  sessionStorage.clear()
  vi.spyOn(solutionsApi, 'generateProposal').mockResolvedValue({ id: 'candidate', kind: 'REQUIREMENTS', status,
    requirementVersionId: 'req-1', expectedRevision: 0, failureCode: null, result: null } as never)
  const wrapper = mount(SolutionProposalPanel, { props: { actorId: 'user-1', solutionId: 'solution-1',
    kind: 'requirements', revision: 0, currentTexts: [], disabled: false } })
  await wrapper.get('[data-action="generate-proposal"]').trigger('click')
  await flushPromises()
  expect(wrapper.find('[data-action="discard-proposal"]').exists()).toBe(false)
  expect(wrapper.get('[data-action="generate-proposal"]').element).toHaveProperty('disabled', true)
  wrapper.unmount()
  sessionStorage.clear()
  vi.restoreAllMocks()
})
