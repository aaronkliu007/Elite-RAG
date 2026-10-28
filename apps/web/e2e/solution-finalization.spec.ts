import { expect, test } from '@playwright/test'

const at = '2026-10-29T00:00:00Z'

test('风险签认、定稿及 Word 下载在桌面和窄屏均可操作', async ({ page }) => {
  let confirmed = false
  let finalized = false
  let revision = 4
  await page.route(/\/api\/v1\/session\/me$/, (route) => route.fulfill({ json: {
    userId: 'reviewer-1', username: 'reviewer', displayName: '评审员',
  } }))
  await page.route(/\/api\/v1\/session\/csrf$/, (route) => route.fulfill({ json: {
    headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf',
  } }))
  await page.route(/\/api\/v1\/knowledge-spaces\?page=1&pageSize=100$/, (route) =>
    route.fulfill({ json: [{ id: 'space-1', name: '方案空间', description: '', revision: 0,
      createdBy: 'user-1', createdAt: at, allowedActions: ['VIEW'] }] }))
  await page.route(/\/api\/v1\/solutions\/solution-1\/finalization-review$/, (route) =>
    route.fulfill({ json: { solutionId: 'solution-1', revision, risks: [{
      sectionId: 'section-1', sectionTitle: '覆盖多个客户事业部与全部历史项目资料的总体架构设计',
      sectionVersionId: 'adopted-1', claimId: 'claim-1',
      claimText: `目标性能基于客户测试报告进行确认。${'跨区域部署性能指标'.repeat(18)}`,
      riskTypes: ['PERFORMANCE_OR_SLA'], confirmed,
      confirmedBy: confirmed ? 'reviewer-1' : null, confirmedAt: confirmed ? at : null,
    }] } }))
  await page.route(/\/api\/v1\/solutions\/solution-1\/risk-confirmations$/, (route) => {
    expect(route.request().postDataJSON().reason).toBe('已核验客户测试报告')
    confirmed = true
    return route.fulfill({ json: { id: 'confirmation-1' } })
  })
  await page.route(/\/api\/v1\/solutions\/solution-1\/finalize$/, (route) => {
    expect(route.request().postDataJSON().expectedRevision).toBe(4)
    finalized = true
    revision += 1
    return route.fulfill({ json: { id: 'final-1', solutionRevision: 1,
      contentHash: 'a'.repeat(64), createdAt: at } })
  })
  await page.route(/\/api\/v1\/solutions\/solution-1\/finalized-versions\/final-1\/word$/, (route) =>
    route.fulfill({ body: 'mock-docx', contentType:
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    headers: { 'Content-Disposition': 'attachment; filename=solution-final-1.docx' } }))
  await page.route(/\/api\/v1\/solutions\/solution-1$/, (route) => route.fulfill({ json: {
    id: 'solution-1', spaceId: 'space-1',
    name: `长标题方案${'跨区域智能知识资产建设'.repeat(12)}`,
    phase: finalized ? 'FINALIZED' : 'IN_REVIEW', revision, solutionRevision: 1, createdAt: at,
    requirementVersion: { id: 'req-1', number: 1, createdBy: 'user-1', createdAt: at,
      changeReason: '初版', items: [{ itemId: 'item-1', category: 'UNKNOWN', text: '待确认', sources: [] }] },
    outlineVersion: { id: 'outline-1', number: 1, createdBy: 'user-1', createdAt: at,
      changeReason: '初版', nodes: [{ sectionId: 'section-1', title: '总体架构设计', level: 1,
        order: 1, required: true, objective: '提出架构', retrievalHint: '检索架构资料', riskTypes: [] }] },
    requirementConfirmation: { versionId: 'req-1', confirmedBy: 'reviewer-1', confirmedAt: at, valid: true },
    outlineConfirmation: { versionId: 'outline-1', confirmedBy: 'reviewer-1', confirmedAt: at, valid: true },
    sourceAccessValid: true, adoptedSectionVersionIds: { 'section-1': 'adopted-1' },
    finalizedVersionId: finalized ? 'final-1' : null,
    allowedActions: finalized ? [] : ['CONFIRM_RISK', 'FINALIZE'],
  } }))

  await page.setViewportSize({ width: 1366, height: 900 })
  await page.goto('/solutions/solution-1')
  await expect(page.locator('[data-action="finalize-solution"]')).toBeDisabled()
  await expect(page.getByText('风险类型：性能或服务级别')).toBeVisible()
  expect(await page.locator('body').evaluate((body) => body.scrollWidth)).toBeLessThanOrEqual(1366)
  await page.screenshot({ path: test.info().outputPath('solution-finalization-1366.png'), fullPage: true })
  await page.locator('input[name="risk-reason-claim-1"]').fill('已核验客户测试报告')
  await page.getByRole('button', { name: '确认此项风险' }).click()
  await expect(page.locator('[data-action="finalize-solution"]')).toBeEnabled()
  await page.locator('[data-action="finalize-solution"]').click()
  await expect(page.locator('[data-action="download-word"]')).toBeVisible()
  const downloaded = page.waitForEvent('download')
  await page.locator('[data-action="download-word"]').click()
  expect((await downloaded).suggestedFilename()).toBe('solution-final-1.docx')
  await page.setViewportSize({ width: 620, height: 900 })
  await expect(page.locator('[data-action="download-word"]')).toBeVisible()
  expect(await page.locator('body').evaluate((body) => body.scrollWidth)).toBeLessThanOrEqual(620)
  await page.screenshot({ path: test.info().outputPath('solution-finalization-620.png'), fullPage: true })
})
