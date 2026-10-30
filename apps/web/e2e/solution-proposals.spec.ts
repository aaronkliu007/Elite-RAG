import { expect, test } from '@playwright/test'

test('需求候选展示来源、人工审阅及冲突保留，宽屏与窄屏无溢出', async ({ page }) => {
  const source = { documentId: 'document-' + 'a'.repeat(64), versionId: 'version-' + 'b'.repeat(64),
    locator: { type: 'MARKDOWN', headingPath: ['客户需求', '跨区域协同建设要求'], nodeId: 'node-1' } }
  const original = '原始需求：' + '建设企业知识库并复用组织资产。'.repeat(8)
  const candidate = '整理后的候选：' + '明确各部门职责、权限和交付物。'.repeat(12)
  let generated = 0
  const generationKeys: string[] = []
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/session/csrf')) return route.fulfill({ json: {
      headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf' } })
    if (url.pathname.endsWith('/session/me')) return route.fulfill({ json: {
      userId: 'user-1', username: 'alice', displayName: '爱丽丝' } })
    if (url.pathname.endsWith('/knowledge-spaces')) return route.fulfill({ json: [] })
    if (url.pathname.endsWith('/proposals/requirements')) {
      if (route.request().method() === 'POST') {
        generated++
        generationKeys.push(route.request().headers()['idempotency-key'] ?? '')
      }
      return route.fulfill({ json: { id: 'proposal-1', kind: 'REQUIREMENTS', status: 'SUCCEEDED',
        requirementVersionId: 'req-1', expectedRevision: 0, failureCode: null,
        result: { kind: 'REQUIREMENTS', items: [{ category: 'GOAL', text: candidate, sources: [source] }] } } })
    }
    if (url.pathname.endsWith('/requirements')) return route.fulfill({ status: 409, json: {
      code: 'SOLUTION_CONCURRENT_UPDATE', message: '版本已变化，候选和当前输入已保留', traceId: '', fieldErrors: {} } })
    if (url.pathname.endsWith('/solutions/solution-1')) return route.fulfill({ json: {
      id: 'solution-1', spaceId: 'space-1', name: '跨区域组织知识资产沉淀与复用解决方案'.repeat(6),
      phase: 'REQUIREMENTS_DRAFT', revision: 0, solutionRevision: 1, createdAt: '2026-10-30T00:00:00Z',
      requirementVersion: { id: 'req-1', number: 1, createdBy: 'user-1', createdAt: '2026-10-30T00:00:00Z',
        changeReason: '初始录入', items: [{ itemId: 'item-1', category: 'GOAL', text: original, sources: [source] }] },
      outlineVersion: null, requirementConfirmation: null, outlineConfirmation: null,
      sourceAccessValid: true, adoptedSectionVersionIds: {}, finalizedVersionId: null,
      allowedActions: ['EDIT_REQUIREMENTS'] } })
    return route.fulfill({ status: 404, json: {} })
  })
  await page.goto('/solutions/solution-1')
  await page.getByRole('button', { name: '生成需求整理候选' }).click()
  await expect(page.getByText(candidate, { exact: true })).toBeVisible()
  for (const width of [1920, 1366]) {
    await page.setViewportSize({ width, height: 1000 })
    await expect(page.getByRole('button', { name: '放弃本次候选' })).toBeVisible()
    await page.locator('.proposal-panel').scrollIntoViewIfNeeded()
    await page.screenshot({ path: `test-results/proposal-discard-${width}.png` })
  }
  await page.getByRole('button', { name: '放弃本次候选' }).click()
  await expect(page.getByText(candidate, { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '生成需求整理候选' }).click()
  await expect(page.getByText(candidate, { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '采纳为待确认修订' })).toBeDisabled()
  await page.getByRole('checkbox', { name: '我已审阅候选内容和来源，知晓仍需异人确认' }).check()
  await page.getByRole('button', { name: '采纳为待确认修订' }).click()
  await expect(page.getByRole('alert')).toContainText('版本已变化')
  await expect(page.getByText(candidate, { exact: true })).toBeVisible()
  await expect(page.locator('textarea[name="requirement-text-0"]')).toHaveValue(original)
  expect(generated).toBe(2)
  expect(generationKeys[0]).not.toBe(generationKeys[1])
  for (const width of [1920, 1366, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    await page.locator('.proposal-panel').scrollIntoViewIfNeeded()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: `test-results/proposal-${width}.png`, fullPage: true })
  }
})
