import { expect, test, type Page } from '@playwright/test'

const now = '2026-10-28T10:00:00Z'
const space = {
  id: 'space-1', name: '大型制造集团跨区域协同知识空间', description: '', revision: 0,
  createdBy: 'user-1', createdAt: now, allowedActions: ['VIEW', 'SEARCH', 'CREATE_DOCUMENT'],
}
const error = (code: string, message: string) => ({ code, message, traceId: 'trace-solution', fieldErrors: {} })

async function session(page: Page) {
  await page.route(/\/api\/v1\/session\/csrf$/, (route) => route.fulfill({
    json: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-solution' },
  }))
  await page.route(/\/api\/v1\/session\/me$/, (route) => route.fulfill({
    json: { userId: 'user-1', username: 'alice', displayName: '爱丽丝' },
  }))
  await page.route(/\/api\/v1\/knowledge-spaces\?page=1&pageSize=100$/, (route) =>
    route.fulfill({ json: [space] }))
}

test('创建、修订来源需求、确认需求、保存并确认大纲', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 })
  await session(page)
  let revision = 0
  let phase = 'REQUIREMENTS_DRAFT'
  let requirementId = 'req-1'
  let requirementNumber = 1
  let requirementText = '需要确认客户的目标范围'
  let category = 'UNKNOWN'
  let sources: unknown[] = []
  let requirementConfirmation: unknown = null
  let outline: unknown = null
  let outlineConfirmation: unknown = null
  let savedSourceLocator: unknown = null

  const current = () => ({
    id: 'solution-1', spaceId: space.id,
    name: `超长制造业智能运维解决方案${'跨区域交付'.repeat(7)}`,
    phase, revision, solutionRevision: 1, createdAt: now,
    requirementVersion: { id: requirementId, number: requirementNumber, createdBy: 'user-1',
      createdAt: now, changeReason: '初始录入',
      items: [{ itemId: 'item-1', category, text: requirementText, sources }] },
    outlineVersion: outline, requirementConfirmation, outlineConfirmation,
    sourceAccessValid: true,
    allowedActions: phase === 'REQUIREMENTS_DRAFT' ? ['EDIT_REQUIREMENTS', 'CONFIRM_REQUIREMENTS']
      : phase === 'REQUIREMENTS_CONFIRMED' ? ['EDIT_REQUIREMENTS', 'EDIT_OUTLINE']
        : phase === 'OUTLINE_DRAFT' ? ['EDIT_REQUIREMENTS', 'EDIT_OUTLINE', 'CONFIRM_OUTLINE']
          : ['EDIT_REQUIREMENTS', 'EDIT_OUTLINE'],
  })
  const result = () => ({ solutionId: 'solution-1', revision, requirementVersionId: requirementId,
    outlineVersionId: (outline as { id?: string } | null)?.id ?? null, phase })

  await page.route(/\/api\/v1\/knowledge-spaces\/space-1\/solutions(?:\?.*)?$/, async (route) => {
    if (route.request().method() === 'GET') return route.fulfill({ json: [] })
    const body = route.request().postDataJSON()
    expect(body.actorId).toBeUndefined()
    requirementText = body.items[0].text
    return route.fulfill({ status: 201, json: result() })
  })
  await page.route(/\/api\/v1\/solutions\/solution-1(?:\/.*)?$/, async (route) => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() === 'GET') return route.fulfill({ json: current() })
    const body = route.request().postDataJSON()
    if (path.endsWith('/requirements')) {
      revision += 1
      requirementId = 'req-2'
      requirementNumber = 2
      requirementText = body.items[0].text
      category = body.items[0].category
      sources = body.items[0].sources
      savedSourceLocator = body.items[0].sources[0]?.locator
    } else if (path.endsWith('/requirements/req-2/confirm')) {
      revision += 1
      phase = 'REQUIREMENTS_CONFIRMED'
      requirementConfirmation = { versionId: requirementId, confirmedBy: 'user-2', confirmedAt: now, valid: true }
    } else if (path.endsWith('/outline')) {
      expect(body.changeReason).toBe('依据客户目标编制大纲')
      revision += 1
      phase = 'OUTLINE_DRAFT'
      outline = { id: 'outline-1', number: 1, nodes: body.nodes.map((node: unknown) => ({ ...node as object, sectionId: 'section-1' })),
        createdBy: 'user-1', createdAt: now, changeReason: body.changeReason }
    } else if (path.endsWith('/outline/outline-1/confirm')) {
      revision += 1
      phase = 'OUTLINE_CONFIRMED'
      outlineConfirmation = { versionId: 'outline-1', confirmedBy: 'user-2', confirmedAt: now, valid: true }
    } else throw new Error(path)
    return route.fulfill({ json: result() })
  })
  await page.route(/\/api\/v1\/search$/, (route) => route.fulfill({ json: {
    requestId: 'search-1', evidence: [{ evidenceId: 'evidence-1', documentId: 'document-1',
      versionId: 'version-1', nodeId: 'node-1', title: '客户需求说明书', excerpt: '目标为提升日常交付效率。',
      score: 0.9, locator: { type: 'PAGE', pageNumber: 3, boundingBox: null } }],
    degraded: false, degradationReasons: [],
    stats: { lexicalCount: 1, vectorCount: 1, fusedCount: 1, reauthorizedCount: 1, selectedCount: 1 },
    completedAt: now,
  } }))

  await page.goto('/spaces/space-1/solutions')
  await page.locator('input[name="solution-name"]').fill('超长制造业智能运维解决方案')
  await page.locator('textarea[name="initial-requirement"]').fill('需要确认客户的目标范围')
  await page.getByRole('button', { name: '创建方案' }).click()
  await expect(page).toHaveURL(/\/solutions\/solution-1$/)
  await page.locator('select').first().selectOption('GOAL')
  await page.locator('textarea[name="requirement-text-0"]').fill('目标为提升日常交付效率')
  await page.locator('input[name="source-query-0"]').fill('客户目标')
  await page.getByRole('button', { name: '检索来源' }).click()
  await expect(page.getByText('客户需求说明书')).toBeVisible()
  await page.getByRole('button', { name: '选用来源' }).click()
  await page.locator('input[name="change-reason"]').fill('补充客户目标和依据')
  await page.locator('[data-action="save-requirements"]').click()
  await expect(page.locator('.version-tag').first()).toHaveText('V2')
  expect(savedSourceLocator).toEqual({ type: 'PAGE', pageNumber: 3 })
  await page.locator('[data-action="confirm-requirements"]').click()
  await expect(page.getByText('步骤 2')).toBeVisible()
  await page.locator('input[name="outline-title-0"]').fill('项目概述')
  await page.getByLabel('章节目标').fill('说明客户目标和方案范围')
  await page.getByLabel('检索提示').fill('查找客户需求和范围')
  await page.locator('input[name="outline-change-reason"]').fill('依据客户目标编制大纲')
  await page.locator('[data-action="save-outline"]').click()
  await expect(page.locator('[data-action="confirm-outline"]')).toBeVisible()
  await page.locator('[data-action="confirm-outline"]').click()
  await expect(page.locator('.phase-tag')).toHaveText('大纲已确认')
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 1366)
  await page.screenshot({ path: test.info().outputPath('solution-workbench-1366.png'), fullPage: true })
})

test('无权操作隐藏，409 保留草稿且窄屏主操作可见', async ({ page }) => {
  await page.setViewportSize({ width: 620, height: 900 })
  await session(page)
  const longName = `跨区域工业知识库方案${'超长方案名称'.repeat(12)}`
  let canEdit = false
  await page.route(/\/api\/v1\/solutions\/solution-2$/, (route) => route.fulfill({ json: {
    id: 'solution-2', spaceId: space.id, name: longName, phase: 'REQUIREMENTS_DRAFT',
    revision: 0, solutionRevision: 1, createdAt: now,
    requirementVersion: { id: 'req-3', number: 1, items: [{ itemId: 'item-3', category: 'UNKNOWN', text: '待确认', sources: [] }],
      createdBy: 'user-1', createdAt: now, changeReason: '初始录入' },
    outlineVersion: null, requirementConfirmation: null, outlineConfirmation: null,
    sourceAccessValid: true, allowedActions: canEdit ? ['EDIT_REQUIREMENTS'] : [],
  } }))
  await page.route(/\/api\/v1\/solutions\/solution-2\/requirements$/, (route) => route.fulfill({
    status: 409, json: error('SOLUTION_CONCURRENT_UPDATE', '并发修订冲突'),
  }))
  await page.goto('/solutions/solution-2')
  await expect(page.locator('[data-action="save-requirements"]')).toHaveCount(0)
  await expect(page.locator('[data-action="confirm-requirements"]')).toHaveCount(0)
  canEdit = true
  await page.reload()
  await page.locator('textarea[name="requirement-text-0"]').fill('输入未保存的目标内容')
  await page.locator('input[name="change-reason"]').fill('更新目标')
  await page.locator('[data-action="save-requirements"]').click()
  await expect(page.getByRole('alert')).toContainText('并发修订冲突')
  await expect(page.locator('textarea[name="requirement-text-0"]')).toHaveValue('输入未保存的目标内容')
  await expect(page.locator('[data-action="reload-conflict"]')).toBeVisible()
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 620)
  await page.screenshot({ path: test.info().outputPath('solution-workbench-620.png'), fullPage: true })
})

test('章节生成、核验、人工采纳及窄屏冲突提示', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 })
  await session(page)
  const evidenceId = '00000000-0000-0000-0000-000000000003'
  let adopted = false
  let generationCalls = 0
  const requestBodies: Record<string, unknown>[] = []
  const generationKeys: string[] = []
  const current = () => ({
    id: 'solution-3', spaceId: space.id,
    name: `跨区域方案${'长期交付'.repeat(16)}`,
    phase: adopted ? 'IN_REVIEW' : 'OUTLINE_CONFIRMED', revision: adopted ? 4 : 3,
    solutionRevision: 1, createdAt: now,
    requirementVersion: { id: 'req-3', number: 1, items: [{ itemId: 'item-3',
      category: 'UNKNOWN', text: '待明确', sources: [] }], createdBy: 'user-1', createdAt: now,
      changeReason: '初始录入' },
    outlineVersion: { id: 'outline-3', number: 1, createdBy: 'user-1', createdAt: now,
      changeReason: '形成大纲', nodes: [{ sectionId: 'section-3',
        title: `超长章节${'技术边界'.repeat(15)}`, level: 1, order: 1, required: true,
        objective: '说明技术边界', retrievalHint: '查找客户材料', riskTypes: [] }] },
    requirementConfirmation: { versionId: 'req-3', confirmedBy: 'user-2', confirmedAt: now, valid: true },
    outlineConfirmation: { versionId: 'outline-3', confirmedBy: 'user-2', confirmedAt: now, valid: true },
    sourceAccessValid: true, adoptedSectionVersionIds: adopted ? { 'section-3': 'adopted-3' } : {},
    allowedActions: ['ADOPT_SECTION'],
  })
  await page.route(/\/api\/v1\/solutions\/solution-3(?:\/.*)?$/, async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path.endsWith('/solution-3')) return route.fulfill({ json: current() })
    if (request.method() === 'GET' && path.endsWith('/finalization-review')) {
      return route.fulfill({ json: { solutionId: 'solution-3', revision: 4, risks: [{
        sectionId: 'section-3', sectionTitle: '超长章节', sectionVersionId: 'adopted-3',
        claimId: 'claim-3', claimText: '客户计划支持100名用户', riskTypes: [],
        confirmed: false, confirmedBy: null, confirmedAt: null,
      }] } })
    }
    if (path.endsWith('/generation-attempts') && request.method() === 'POST') {
      requestBodies.push(request.postDataJSON() as Record<string, unknown>)
      generationKeys.push(request.headers()['idempotency-key'] ?? '')
      if (adopted) return route.fulfill({ status: 409,
        json: error('SOLUTION_CONCURRENT_UPDATE', '方案已由他人修改') })
      generationCalls += 1
      if (generationCalls === 1) return route.fulfill({ json: { id: 'attempt-rejected',
        status: 'SUCCEEDED', executionCount: 1, revision: 4, failureCode: null,
        sectionVersion: { id: 'draft-rejected', content: '## 总体方案\n客户计划支持200名用户。',
          evidenceIds: [evidenceId], claims: [{ claimId: 'claim-rejected',
            text: '客户计划支持200名用户', factual: true, evidenceIds: [evidenceId], riskTypes: [] }],
          verification: { verifications: [{ claimId: 'claim-rejected', verdict: 'UNSUPPORTED',
            reason: 'QUANTIFIED_VALUE_MISMATCH' }] } } } })
      return route.fulfill({ json: { id: 'attempt-3', status: 'SUCCEEDED', executionCount: 1,
        revision: 4, failureCode: null, sectionVersion: { id: 'draft-3',
          content: '## 总体方案\n客户计划支持100名用户。', evidenceIds: [evidenceId],
          claims: [{ claimId: 'claim-3', text: '客户计划支持100名用户', factual: true,
            evidenceIds: [evidenceId], riskTypes: [] }],
          verification: { verifications: [{ claimId: 'claim-3', verdict: 'HUMAN_CONFIRMATION_REQUIRED',
            reason: 'NEEDS_REVIEW' }] } } } })
    }
    if (path.endsWith('/adopt') && request.method() === 'POST') {
      expect(request.postDataJSON()).toEqual({ expectedRevision: 3 })
      adopted = true
      return route.fulfill({ json: { solutionId: 'solution-3', revision: 4,
        requirementVersionId: 'req-3', outlineVersionId: 'outline-3', phase: 'IN_REVIEW' } })
    }
    throw new Error(path)
  })
  await page.goto('/solutions/solution-3')
  await page.locator('[data-action="generate-section"]').focus()
  await expect(page.locator('[data-action="generate-section"]')).toHaveCSS('outline-style', 'solid')
  await page.locator('[data-action="generate-section"]').click()
  await expect(page.getByText('不支持，禁止采纳')).toBeVisible()
  await expect(page.locator('[data-action="adopt-section"]')).toHaveCount(0)
  await page.locator('[data-action="generate-section"]').click()
  await expect(page.getByText('需人工确认')).toBeVisible()
  await expect(page.locator('[data-action="adopt-section"]')).toBeDisabled()
  expect(generationKeys[0]).toBeTruthy()
  expect(generationKeys[1]).toBeTruthy()
  expect(generationKeys[1]).not.toBe(generationKeys[0])
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 1366)
  await page.screenshot({ path: test.info().outputPath('solution-generation-1366.png'), fullPage: true })
  await page.setViewportSize({ width: 620, height: 900 })
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 620)
  await page.screenshot({ path: test.info().outputPath('solution-draft-620.png'), fullPage: true })
  await page.locator('[data-action="review-section"]').check()
  await page.locator('[data-action="adopt-section"]').click()
  await expect(page.getByText('当前已采纳版本：adopted-3')).toBeVisible()
  expect(requestBodies[0]).toEqual({ expectedRevision: 3, baselineSectionVersionId: null })
  expect(requestBodies[1]).toEqual({ expectedRevision: 3, baselineSectionVersionId: null })
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 620)
  await page.locator('[data-action="generate-section"]').click()
  await expect(page.getByRole('alert')).toContainText('方案已由他人修改')
  await expect(page.locator('[data-action="reload-generation-conflict"]')).toBeVisible()
  expect(requestBodies[2]).toEqual({ expectedRevision: 4, baselineSectionVersionId: 'adopted-3' })
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 620)
  await page.screenshot({ path: test.info().outputPath('solution-generation-620.png'), fullPage: true })
})
