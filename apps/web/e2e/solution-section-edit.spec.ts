import { expect, test } from '@playwright/test'

const at = '2026-10-29T04:00:00Z'
const baseline = { id: 'adopted-1', kind: 'ADOPTED',
  content: `原始实施方案。${'跨部门知识复用与权限校验。'.repeat(20)}`,
  parentVersionId: 'draft-1', baselineVersionId: null, createdBy: 'editor-1', createdAt: at,
  claims: [{ claimId: 'claim-1', text: '原始实施方案', factual: true,
    evidenceIds: ['evidence-1'], riskTypes: [] }], verification: { verifications: [] } }
const revised = { ...baseline, id: 'human-1', kind: 'HUMAN_EDIT',
  content: `人工修订方案。${'跨部门知识复用与权限校验。'.repeat(20)}`,
  parentVersionId: 'adopted-1', baselineVersionId: 'adopted-1',
  verification: { verifications: [{ claimId: 'claim-2', verdict: 'HUMAN_CONFIRMATION_REQUIRED',
    reasonCode: 'HUMAN_EDIT_REVIEW_REQUIRED' }] } }

test('章节人工修订与版本对比在桌面和窄屏保持可读', async ({ page }) => {
  let saved = false
  let adopted = false
  await page.route(/\/api\/v1\/session\/me$/, route => route.fulfill({ json: {
    userId: 'editor-1', username: 'editor', displayName: '编辑员',
  } }))
  await page.route(/\/api\/v1\/session\/csrf$/, route => route.fulfill({ json: {
    headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf',
  } }))
  await page.route(/\/api\/v1\/knowledge-spaces\?page=1&pageSize=100$/, route =>
    route.fulfill({ json: [{ id: 'space-1', name: '方案空间', description: '', revision: 0,
      createdBy: 'editor-1', createdAt: at, allowedActions: ['VIEW'] }] }))
  await page.route(/\/api\/v1\/solutions\/solution-1\/finalization-review$/, route =>
    route.fulfill({ json: { solutionId: 'solution-1', revision: 4, risks: [] } }))
  await page.route(/\/api\/v1\/solutions\/solution-1\/sections\/section-1\/versions$/, route =>
    route.fulfill({ json: saved ? [revised, baseline] : [baseline] }))
  await page.route(/\/api\/v1\/solutions\/solution-1\/sections\/section-1\/edits\/latest\?/, route =>
    route.fulfill({ json: { version: saved ? revised : null } }))
  await page.route(/\/api\/v1\/solutions\/solution-1\/sections\/section-1\/versions\/adopted-1$/, route =>
    route.fulfill({ json: baseline }))
  await page.route(/\/api\/v1\/solutions\/solution-1\/sections\/section-1\/versions\/human-1$/, route =>
    route.fulfill({ json: revised }))
  await page.route(/\/api\/v1\/solutions\/solution-1\/sections\/section-1\/edits$/, route => {
    expect(route.request().postDataJSON()).toMatchObject({ baselineVersionId: 'adopted-1',
      expectedDraftVersionId: null, expectedRevision: 4, content: revised.content })
    saved = true
    return route.fulfill({ json: revised })
  })
  await page.route(/\/api\/v1\/solutions\/solution-1\/sections\/section-1\/versions\/human-1\/adopt$/, route => {
    expect(route.request().postDataJSON()).toEqual({ expectedRevision: 4 })
    adopted = true
    return route.fulfill({ json: { solutionId: 'solution-1', revision: 5 } })
  })
  await page.route(/\/api\/v1\/solutions\/solution-1$/, route => route.fulfill({ json: {
    id: 'solution-1', spaceId: 'space-1', name: '内部知识资产方案',
    phase: 'IN_REVIEW', revision: adopted ? 5 : 4, solutionRevision: 1, createdAt: at,
    requirementVersion: { id: 'req-1', number: 1, createdBy: 'editor-1', createdAt: at,
      changeReason: '初版', items: [{ itemId: 'item-1', category: 'UNKNOWN', text: '待核实', sources: [] }] },
    outlineVersion: { id: 'outline-1', number: 1, createdBy: 'editor-1', createdAt: at,
      changeReason: '初版', nodes: [{ sectionId: 'section-1', title: `总体设计${'跨区域长标题'.repeat(12)}`,
        level: 1, order: 1, required: true, objective: '说明方案', retrievalHint: '方案资料', riskTypes: [] }] },
    requirementConfirmation: { versionId: 'req-1', confirmedBy: 'reviewer-1', confirmedAt: at, valid: true },
    outlineConfirmation: { versionId: 'outline-1', confirmedBy: 'reviewer-1', confirmedAt: at, valid: true },
    sourceAccessValid: true, adoptedSectionVersionIds: { 'section-1': adopted ? 'human-adopted-1' : 'adopted-1' },
    finalizedVersionId: null, allowedActions: ['ADOPT_SECTION'],
  } }))
  await page.setViewportSize({ width: 1366, height: 900 })
  await page.goto('/solutions/solution-1')
  await page.locator('[data-action="open-section-editor"]').click()
  await expect(page.locator('[data-action="section-edit-content"]')).toBeVisible()
  await page.locator('[data-action="section-edit-content"]').fill(revised.content)
  await page.locator('[data-action="save-section-edit"]').click()
  await expect(page.getByText('两个版本正文存在差异，请逐段核对')).toBeVisible()
  expect(await page.locator('body').evaluate(body => body.scrollWidth)).toBeLessThanOrEqual(1366)
  await page.screenshot({ path: test.info().outputPath('section-edit-1366.png'), fullPage: true })
  await page.setViewportSize({ width: 620, height: 900 })
  expect(await page.locator('body').evaluate(body => body.scrollWidth)).toBeLessThanOrEqual(620)
  await expect(page.locator('[data-action="compare-right"]')).toBeVisible()
  await page.screenshot({ path: test.info().outputPath('section-edit-620.png'), fullPage: true })
  await page.locator('[data-action="review-section-edit"]').check()
  await page.locator('[data-action="adopt-section-edit"]').click()
  await expect(page.locator('[data-action="open-section-editor"]')).toBeVisible()
})
