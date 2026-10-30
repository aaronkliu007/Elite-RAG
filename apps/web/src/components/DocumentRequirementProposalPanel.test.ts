import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import DocumentRequirementProposalPanel from './DocumentRequirementProposalPanel.vue'
import { documentsApi } from '../api/documents'
import { solutionsApi } from '../api/solutions'
import { ApiRequestError } from '../api/http'

const documents = [{ id: 'doc-1', title: '客户需求说明', currentPublishedVersionId: 'version-1',
  versions: [{ id: 'version-1', status: 'PUBLISHED', parseStatus: 'SUCCEEDED' }], archived: false, allowedActions: ['VIEW'] }]
const proposal = { id: 'proposal-1', kind: 'DOCUMENT_REQ', status: 'SUCCEEDED', requirementVersionId: 'req-1',
  expectedRevision: 0, failureCode: null, result: { kind: 'REQUIREMENTS', documentsWithoutEvidence: [],
    retrievalDegraded: false, items: [{ category: 'GOAL', text: '识别到的需求', sources: [{ documentId: 'doc-1',
      versionId: 'version-1', locator: { type: 'MARKDOWN', headingPath: ['范围'], nodeId: 'node-1' },
      evidenceIds: ['evidence-1'] }] }] } }
const props = { actorId: 'user-1', solutionId: 'solution-1', spaceId: 'space-1', revision: 0,
  currentTexts: ['原有需求'], disabled: false }

it('选择已发布文档，审阅候选后保存为待确认修订', async () => {
  sessionStorage.clear()
  vi.spyOn(documentsApi, 'list').mockResolvedValue(documents as never)
  const generate = vi.spyOn(solutionsApi, 'generateDocumentProposal').mockResolvedValue(proposal as never)
  const save = vi.spyOn(solutionsApi, 'adoptDocumentProposal').mockResolvedValue({} as never)
  const wrapper = mount(DocumentRequirementProposalPanel, { props })
  await flushPromises()
  await wrapper.get('[data-document="doc-1"]').setValue(true)
  await wrapper.get('[data-action="document-query"]').setValue('提取建设目标')
  await wrapper.get('[data-action="generate-document-proposal"]').trigger('click')
  await flushPromises()
  expect(generate).toHaveBeenCalledWith('solution-1', '提取建设目标',
    [{ documentId: 'doc-1', versionId: 'version-1' }], 0, expect.any(String))
  expect(wrapper.text()).toContain('识别到的需求')
  expect(wrapper.get('[data-action="adopt-document-proposal"]').element).toHaveProperty('disabled', true)
  await wrapper.get('[data-action="review-document-proposal"]').setValue(true)
  await wrapper.get('[data-action="adopt-document-proposal"]').trigger('click')
  await flushPromises()
  expect(save).toHaveBeenCalledWith('solution-1', '提取建设目标',
    [{ documentId: 'doc-1', versionId: 'version-1' }], 0, expect.any(String), expect.any(String))
  expect(wrapper.emitted('adopted')).toHaveLength(1)
  wrapper.unmount(); sessionStorage.clear(); vi.restoreAllMocks()
})

it('采纳响应丢失后，新修订页面仍可用原采纳键核对结果', async () => {
  sessionStorage.clear()
  vi.spyOn(documentsApi, 'list').mockResolvedValue(documents as never)
  vi.spyOn(solutionsApi, 'generateDocumentProposal').mockResolvedValue(proposal as never)
  const save = vi.spyOn(solutionsApi, 'adoptDocumentProposal')
    .mockRejectedValueOnce(new ApiRequestError('网络中断', 503, 'SERVICE_UNAVAILABLE', '', {}))
    .mockResolvedValueOnce({} as never)
  const first = mount(DocumentRequirementProposalPanel, { props })
  await flushPromises()
  await first.get('[data-document="doc-1"]').setValue(true)
  await first.get('[data-action="document-query"]').setValue('提取建设目标')
  await first.get('[data-action="generate-document-proposal"]').trigger('click')
  await flushPromises()
  await first.get('[data-action="review-document-proposal"]').setValue(true)
  await first.get('[data-action="adopt-document-proposal"]').trigger('click')
  await flushPromises()
  first.unmount()
  const second = mount(DocumentRequirementProposalPanel, { props: { ...props, revision: 1 } })
  await flushPromises()
  await second.get('[data-action="recover-document-adoption"]').trigger('click')
  await flushPromises()
  expect(save).toHaveBeenCalledTimes(2)
  expect(save.mock.calls[1]).toEqual(save.mock.calls[0])
  expect(second.emitted('adopted')).toHaveLength(1)
  second.unmount(); sessionStorage.clear(); vi.restoreAllMocks()
})

it('通过快照来源编号核对片段，撤权时隐藏已显示候选', async () => {
  sessionStorage.clear()
  vi.spyOn(documentsApi, 'list').mockResolvedValue(documents as never)
  vi.spyOn(solutionsApi, 'generateDocumentProposal').mockResolvedValue(proposal as never)
  const preview = vi.spyOn(solutionsApi, 'getDocumentProposalEvidence')
    .mockResolvedValueOnce({ text: '已授权的原始片段' })
    .mockRejectedValueOnce(new ApiRequestError('来源不可访问', 404, 'NOT_FOUND', '', {}))
  const wrapper = mount(DocumentRequirementProposalPanel, { props })
  await flushPromises()
  await wrapper.get('[data-document="doc-1"]').setValue(true)
  await wrapper.get('[data-action="document-query"]').setValue('提取建设目标')
  await wrapper.get('[data-action="generate-document-proposal"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-evidence="evidence-1"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('已授权的原始片段')
  expect(preview).toHaveBeenCalledWith('solution-1', 'evidence-1', '提取建设目标',
    [{ documentId: 'doc-1', versionId: 'version-1' }], 0, expect.any(String))
  await wrapper.get('[data-evidence="evidence-1"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('已授权的原始片段')
  expect(wrapper.text()).not.toContain('识别到的需求')
  wrapper.unmount(); sessionStorage.clear(); vi.restoreAllMocks()
})

it('结果不确定时刷新页面会用原键和原输入查询，不重复生成', async () => {
  sessionStorage.clear()
  vi.spyOn(documentsApi, 'list').mockResolvedValue(documents as never)
  const generate = vi.spyOn(solutionsApi, 'generateDocumentProposal').mockRejectedValue(
    new ApiRequestError('结果不确定', 503, 'MODEL_PROPOSAL_UNCERTAIN', '', {}))
  const lookup = vi.spyOn(solutionsApi, 'getDocumentProposal').mockResolvedValue({ ...proposal,
    status: 'RUNNING', result: null } as never)
  const first = mount(DocumentRequirementProposalPanel, { props })
  await flushPromises()
  await first.get('[data-document="doc-1"]').setValue(true)
  await first.get('[data-action="document-query"]').setValue('提取建设目标')
  await first.get('[data-action="generate-document-proposal"]').trigger('click')
  await flushPromises()
  const key = generate.mock.calls[0]?.[4]
  first.unmount()
  const second = mount(DocumentRequirementProposalPanel, { props })
  await flushPromises()
  expect(generate).toHaveBeenCalledTimes(1)
  expect(lookup).toHaveBeenCalledWith('solution-1', '提取建设目标',
    [{ documentId: 'doc-1', versionId: 'version-1' }], 0, key)
  expect(second.find('[data-action="discard-document-proposal"]').exists()).toBe(false)
  second.unmount(); sessionStorage.clear(); vi.restoreAllMocks()
})

it('原请求不可访问时可显式更换文档且不会自动再次调用模型', async () => {
  sessionStorage.clear()
  vi.spyOn(documentsApi, 'list').mockResolvedValue(documents as never)
  const generate = vi.spyOn(solutionsApi, 'generateDocumentProposal').mockRejectedValue(
    new ApiRequestError('无权访问', 404, 'NOT_FOUND', '', {}))
  const wrapper = mount(DocumentRequirementProposalPanel, { props })
  await flushPromises()
  await wrapper.get('[data-document="doc-1"]').setValue(true)
  await wrapper.get('[data-action="document-query"]').setValue('提取建设目标')
  await wrapper.get('[data-action="generate-document-proposal"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('原请求可能已经调用过模型')
  await wrapper.findAll('button').find(button => button.text() === '放弃原请求并更换文档')!.trigger('click')
  expect(generate).toHaveBeenCalledTimes(1)
  expect(wrapper.get('[data-action="document-query"]').element).toHaveProperty('disabled', false)
  expect(wrapper.text()).toContain('已选择 0 / 10 个文档')
  wrapper.unmount(); sessionStorage.clear(); vi.restoreAllMocks()
})

it('采纳文档撤权时允许用户放弃追踪并清空已选文档', async () => {
  sessionStorage.clear()
  vi.spyOn(documentsApi, 'list').mockResolvedValue(documents as never)
  vi.spyOn(solutionsApi, 'generateDocumentProposal').mockResolvedValue(proposal as never)
  vi.spyOn(solutionsApi, 'adoptDocumentProposal').mockRejectedValue(
    new ApiRequestError('无权访问', 404, 'NOT_FOUND', '', {}))
  const wrapper = mount(DocumentRequirementProposalPanel, { props })
  await flushPromises()
  await wrapper.get('[data-document="doc-1"]').setValue(true)
  await wrapper.get('[data-action="document-query"]').setValue('提取建设目标')
  await wrapper.get('[data-action="generate-document-proposal"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-action="review-document-proposal"]').setValue(true)
  await wrapper.get('[data-action="adopt-document-proposal"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('可能已经保存')
  await wrapper.findAll('button').find(button => button.text() === '放弃追踪并更换文档')!.trigger('click')
  expect(wrapper.text()).toContain('已选择 0 / 10 个文档')
  wrapper.unmount(); sessionStorage.clear(); vi.restoreAllMocks()
})
