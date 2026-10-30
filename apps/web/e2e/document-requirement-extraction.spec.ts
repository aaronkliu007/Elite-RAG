import { expect, test } from '@playwright/test'

test('从文档提取、审阅来源，采纳响应丢失后按原键恢复', async ({ page }) => {
  let revision = 0
  let adoptionCalls = 0
  let genericRevisionCalls = 0
  const adoptionKeys: string[] = []
  const proposalKeys: string[] = []
  const bodies: unknown[] = []
  const docId = 'document-' + 'd'.repeat(60)
  const versionId = 'version-' + 'v'.repeat(60)
  const document = { id: docId, spaceId: 'space-1', title: '跨区域交付的企业知识库客户建设目标和约束说明'.repeat(4),
    archived: false, currentPublishedVersionId: versionId, allowedActions: ['VIEW'], metadata: { classification: '内部', tags: [] },
    versions: [{ id: versionId, versionNumber: 1, status: 'PUBLISHED', parseStatus: 'SUCCEEDED',
      createdAt: '2026-10-30T00:00:00Z', parseErrorCode: null }], reviews: [] }
  const source = { documentId: docId, versionId, locator: { type: 'MARKDOWN', headingPath: ['客户需求', '建设目标'], nodeId: 'node-1' },
    evidenceIds: ['evidence-1'] }
  const candidateText = '根据文档提取建设目标：' + '分阶段建设公司知识库并支持跨部门共享。'.repeat(8)
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname.endsWith('/session/csrf')) return route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'csrf', parameterName: '_csrf' } })
    if (url.pathname.endsWith('/session/me')) return route.fulfill({ json: { userId: 'user-1', username: 'alice', displayName: '爱丽丝' } })
    if (url.pathname === '/api/v1/knowledge-spaces') return route.fulfill({ json: [] })
    if (url.pathname.endsWith('/knowledge-spaces/space-1/documents')) return route.fulfill({ json: [document] })
    if (url.pathname.endsWith('/document-requirement-proposals/adopt')) {
      adoptionCalls++
      adoptionKeys.push(request.headers()['idempotency-key'] ?? '')
      proposalKeys.push(request.headers()['proposal-key'] ?? '')
      bodies.push(request.postDataJSON())
      if (adoptionCalls === 1) {
        revision = 1
        return route.fulfill({ status: 503, json: { code: 'SERVICE_UNAVAILABLE', message: '连接中断', traceId: '', fieldErrors: {} } })
      }
      return route.fulfill({ json: { solutionId: 'solution-1', revision: 1, requirementVersionId: 'req-2',
        outlineVersionId: null, phase: 'REQUIREMENTS_DRAFT' } })
    }
    if (url.pathname.endsWith('/document-requirement-proposals/evidence/evidence-1'))
      return route.fulfill({ json: { text: '客户原始需求片段：建设公司知识库。' } })
    if (url.pathname.endsWith('/document-requirement-proposals')) return route.fulfill({ json: {
      id: 'attempt-1', kind: 'DOCUMENT_REQ', status: 'SUCCEEDED', requirementVersionId: 'req-1', expectedRevision: 0,
      failureCode: null, result: { kind: 'REQUIREMENTS', retrievalDegraded: false, documentsWithoutEvidence: [],
        items: [{ itemId: 'item-2', category: 'GOAL', text: candidateText, sources: [source] }] } } })
    if (url.pathname.endsWith('/requirements') && request.method() === 'PUT') { genericRevisionCalls++; return route.fulfill({ status: 500 }) }
    if (url.pathname.endsWith('/solutions/solution-1')) return route.fulfill({ json: {
      id: 'solution-1', spaceId: 'space-1', name: '跨区域方案建设与知识资产沉淀'.repeat(5),
      phase: 'REQUIREMENTS_DRAFT', revision, solutionRevision: 1, createdAt: '2026-10-30T00:00:00Z',
      requirementVersion: { id: revision ? 'req-2' : 'req-1', number: revision + 1, createdBy: 'user-1',
        createdAt: '2026-10-30T00:00:00Z', changeReason: '初始录入',
        items: [{ itemId: 'item-1', category: 'UNKNOWN', text: '待明确目标', sources: [] }] },
      outlineVersion: null, requirementConfirmation: null, outlineConfirmation: null, sourceAccessValid: true,
      adoptedSectionVersionIds: {}, finalizedVersionId: null, allowedActions: ['EDIT_REQUIREMENTS'] } })
    return route.fulfill({ status: 404, json: {} })
  })
  await page.goto('/solutions/solution-1')
  const panel = page.getByRole('region', { name: '从文档提取需求' })
  await panel.locator(`[data-document="${docId}"]`).check()
  await panel.getByLabel('关注问题').fill('提取建设目标和范围')
  await panel.getByRole('button', { name: '提取需求候选' }).click()
  await expect(panel.getByText(candidateText, { exact: true })).toBeVisible()
  await expect(panel.getByText(/客户需求 \/ 建设目标/)).toBeVisible()
  await panel.getByRole('button', { name: '核对原文片段 1' }).click()
  await expect(panel.getByText('客户原始需求片段：建设公司知识库。')).toBeVisible()
  for (const width of [1920, 1366, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    await panel.scrollIntoViewIfNeeded()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: `test-results/document-extraction-${width}.png` })
  }
  await panel.getByRole('checkbox', { name: '我已核对候选及来源，知晓仍需异人确认' }).check()
  await panel.getByRole('button', { name: '采纳为待确认修订' }).click()
  await expect(panel.getByRole('alert')).toContainText('连接中断')
  await page.reload()
  const recovered = page.getByRole('region', { name: '从文档提取需求' })
  await recovered.getByRole('button', { name: '查询原采纳结果' }).click()
  await expect.poll(() => adoptionCalls).toBe(2)
  expect(adoptionKeys[0]).toBeTruthy()
  expect(adoptionKeys[1]).toBe(adoptionKeys[0])
  expect(proposalKeys[1]).toBe(proposalKeys[0])
  expect(bodies[1]).toEqual(bodies[0])
  expect(genericRevisionCalls).toBe(0)
})
