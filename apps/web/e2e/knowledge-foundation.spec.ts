import { expect, test, type Page } from '@playwright/test'

const currentUser = {
  userId: 'user-1',
  username: 'zhangsan',
  displayName: '张三',
}

const space = {
  id: 'space-1',
  name: '解决方案与交付知识空间',
  description: '公司级方案、评审记录与交付模板',
  revision: 1,
  createdBy: 'user-1',
  createdAt: '2026-09-24T10:00:00Z',
  allowedActions: ['VIEW', 'SEARCH', 'CREATE_DOCUMENT'],
}

const apiError = (message: string, code = 'REQUEST_FAILED') => ({
  code,
  message,
  traceId: 'trace-e2e',
  fieldErrors: {},
})

function documentDetail(overrides: Record<string, unknown> = {}) {
  return {
    id: 'document-1',
    spaceId: 'space-1',
    title: '制造行业智能运维解决方案',
    metadata: { classification: '内部', tags: ['制造', '运维'] },
    versions: [{
      id: 'version-1',
      versionNumber: 1,
      status: 'IN_REVIEW',
      parseStatus: 'SUCCEEDED',
      createdAt: '2026-09-24T10:00:00Z',
      parseErrorCode: null,
    }],
    currentPublishedVersionId: null,
    archived: false,
    reviews: [],
    allowedActions: ['VIEW', 'PREVIEW', 'DOWNLOAD', 'REJECT', 'PUBLISH'],
    ...overrides,
  }
}

async function mockSession(page: Page) {
  await page.route(/\/api\/v1\/session\/csrf$/, (route) => route.fulfill({
    json: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-e2e' },
  }))
  await page.route(/\/api\/v1\/session\/me$/, (route) => route.fulfill({ json: currentUser }))
}

async function mockSpaces(page: Page, spaces = [space]) {
  await page.route(/\/api\/v1\/knowledge-spaces\?page=1&pageSize=100$/, (route) =>
    route.fulfill({ json: spaces }),
  )
}

test.beforeEach(async ({ page }) => {
  await mockSession(page)
})

test('撤销权限后直链返回统一的不可发现结果', async ({ page }) => {
  await page.route(/\/api\/v1\/documents\/secret-document$/, (route) => route.fulfill({
    status: 404,
    json: apiError('资源不存在或无权访问', 'NOT_FOUND'),
  }))

  await page.goto('/documents/secret-document')

  await expect(page.getByText('无法查看此文档', { exact: true })).toBeVisible()
  await expect(page.getByText(/资源不存在或无权访问/)).toBeVisible()
  await expect(page.getByText('绝密客户并购方案')).toHaveCount(0)
})

test('上传失败重试复用幂等键并进入文档解析页', async ({ page }) => {
  await mockSpaces(page)
  const idempotencyKeys: string[] = []
  let uploadAttempts = 0
  await page.route(/\/api\/v1\/knowledge-spaces\/space-1\/documents$/, async (route) => {
    uploadAttempts += 1
    idempotencyKeys.push(route.request().headers()['idempotency-key'] ?? '')
    if (uploadAttempts === 1) {
      await route.fulfill({ status: 503, json: apiError('上传服务暂时不可用', 'SERVICE_UNAVAILABLE') })
      return
    }
    await route.fulfill({
      status: 202,
      json: { documentId: 'document-1', versionId: 'version-1', status: 'ACCEPTED' },
    })
  })
  await page.route(/\/api\/v1\/documents\/document-1$/, (route) => route.fulfill({
    json: documentDetail({
      versions: [{
        id: 'version-1', versionNumber: 1, status: 'DRAFT', parseStatus: 'PENDING',
        createdAt: '2026-09-24T10:00:00Z', parseErrorCode: null,
      }],
      allowedActions: ['VIEW', 'PREVIEW', 'DOWNLOAD', 'SUBMIT_REVIEW'],
    }),
  }))

  await page.goto('/upload')
  await page.locator('select[name="spaceId"]').selectOption('space-1')
  await page.locator('input[name="title"]').fill('制造行业智能运维解决方案')
  await page.locator('select[name="classification"]').selectOption('内部')
  await page.locator('input[name="tags"]').fill('制造, 运维')
  await page.locator('input[name="file"]').setInputFiles({
    name: 'solution.md',
    mimeType: 'text/markdown',
    buffer: Buffer.from('# 解决方案\n受控知识内容'),
  })

  await page.getByTestId('upload-submit').click()
  await expect(page.getByRole('alert')).toContainText('上传服务暂时不可用')
  await page.getByTestId('upload-submit').click()

  await expect(page).toHaveURL(/\/documents\/document-1$/)
  await expect(page.locator('.status-tag')).toHaveText('等待解析')
  expect(uploadAttempts).toBe(2)
  expect(idempotencyKeys[0]).not.toBe('')
  expect(idempotencyKeys[1]).toBe(idempotencyKeys[0])
})

test('解析轮询完成后可发布，且 1366px 与宽屏下主操作不被遮挡', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 })
  const longTitle = `某大型制造集团跨区域一体化智能运维与数据治理总体解决方案${'超长标题'.repeat(10)}`
  let reads = 0
  await page.route(/\/api\/v1\/documents\/document-1$/, (route) => {
    reads += 1
    const ready = reads > 1
    return route.fulfill({
      json: documentDetail({
        title: longTitle,
        versions: [{
          id: 'version-1', versionNumber: 1, status: ready ? 'IN_REVIEW' : 'DRAFT',
          parseStatus: ready ? 'SUCCEEDED' : 'RUNNING', createdAt: '2026-09-24T10:00:00Z',
          parseErrorCode: null,
        }],
      }),
    })
  })
  await page.route(/\/api\/v1\/documents\/document-1\/versions\/version-1\/publish$/, (route) =>
    route.fulfill({
      json: documentDetail({
        title: longTitle,
        versions: [{
          id: 'version-1', versionNumber: 1, status: 'PUBLISHED', parseStatus: 'SUCCEEDED',
          createdAt: '2026-09-24T10:00:00Z', parseErrorCode: null,
        }],
        currentPublishedVersionId: 'version-1',
      }),
    }),
  )

  await page.goto('/documents/document-1')
  const publish = page.locator('[data-action="publish"]')
  await expect(page.locator('.status-tag')).toHaveText('解析中')
  await expect(publish).toBeDisabled()
  await expect(page.locator('.status-tag')).toHaveText('解析成功', { timeout: 5_000 })
  await expect(publish).toBeEnabled()
  await publish.click()
  await expect(page.getByText('当前版本已发布。')).toBeVisible()
  await expect(page.getByText('V1 · PUBLISHED')).toBeVisible()
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 1366)
  await page.screenshot({ path: test.info().outputPath('document-review-1366.png'), fullPage: true })

  await page.setViewportSize({ width: 1600, height: 1000 })
  await expect(page.getByRole('heading', { name: longTitle })).toBeVisible()
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 1600)
  await page.screenshot({ path: test.info().outputPath('document-review-wide.png'), fullPage: true })
})

test('只读用户可预览和下载但看不到写操作', async ({ page }) => {
  await page.route(/\/api\/v1\/documents\/document-1$/, (route) => route.fulfill({
    json: documentDetail({ allowedActions: ['VIEW', 'PREVIEW', 'DOWNLOAD'] }),
  }))

  await page.goto('/documents/document-1')

  await expect(page.locator('[data-action="preview"]')).toBeVisible()
  await expect(page.locator('[data-action="download"]')).toBeVisible()
  await expect(page.locator('[data-action="submit-review"]')).toHaveCount(0)
  await expect(page.locator('[data-action="reject"]')).toHaveCount(0)
  await expect(page.locator('[data-action="publish"]')).toHaveCount(0)
})

test('登录用户可从导航执行降级检索并跳转到精确来源', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 })
  await mockSpaces(page)
  await page.route(/\/api\/v1\/search$/, (route) => route.fulfill({
    json: {
      requestId: 'search-1',
      evidence: [{
        evidenceId: 'evidence-1', documentId: 'document-1', versionId: 'version-1', nodeId: 'node-42',
        title: '智能运维交付方案', excerpt: '通过分级巡检与知识复用提升交付效率。', score: 0.91,
        locator: { type: 'PAGE', pageNumber: 12, boundingBox: null },
      }],
      degraded: true,
      degradationReasons: ['VECTOR_UNAVAILABLE'],
      stats: { lexicalCount: 8, vectorCount: 0, fusedCount: 8, reauthorizedCount: 3, selectedCount: 1 },
      completedAt: '2026-09-24T10:00:00Z',
    },
  }))

  await page.goto('/search')
  await expect(page.getByRole('navigation', { name: '主导航' }).getByText('全文检索')).toBeVisible()
  await page.locator('input[name="queryText"]').fill('制造业运维方案')
  await page.getByRole('button', { name: '检索', exact: true }).click()

  await expect(page.getByText('检索已降级')).toBeVisible()
  await expect(page.getByText('通过分级巡检与知识复用提升交付效率。')).toBeVisible()
  const source = page.locator('[data-source-link]')
  await expect(source).toHaveText('PDF 第 12 页 · 查看来源')
  await expect(source).toHaveAttribute('href', '/documents/document-1?source=node-42')
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 1366)
  await page.screenshot({ path: test.info().outputPath('search-degraded-1366.png'), fullPage: true })
})

test('知识问答展示降级回答、引用，并在再次鉴权后打开来源', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 })
  const idempotencyKeys: string[] = []
  await page.route(/\/api\/qa\/questions$/, async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}') as Record<string, unknown>
    expect(Object.keys(body)).toEqual(['question'])
    idempotencyKeys.push(route.request().headers()['idempotency-key'] ?? '')
    await route.fulfill({ json: {
      requestId: 'qa-request-1', status: 'ANSWERED',
      answer: '交付评审需要方案、风险清单和验收口径。', refusal: null, degraded: true,
      citations: [{
        claimId: 'claim-1', text: '交付评审需要准备方案、风险清单和验收口径。',
        evidenceIds: ['evidence-1'],
      }],
      completedAt: '2026-10-27T06:00:00Z',
    } })
  })
  await page.route(/\/api\/qa\/questions\/qa-request-1\/sources\/evidence-1$/, (route) => route.fulfill({ json: {
    documentId: 'document-1', displayName: '交付评审操作手册', versionId: 'version-1',
    location: { type: 'PAGE', coordinates: { pageNumber: 18 } },
    previewUrl: '/api/v1/documents/document-1/preview',
    downloadUrl: '/api/v1/documents/document-1/download',
  } }))
  await page.context().route(/\/api\/v1\/documents\/document-1\/preview$/, (route) => route.fulfill({
    contentType: 'text/plain', body: '经过再次鉴权的来源预览',
  }))

  await page.goto('/qa')
  await expect(page.getByRole('navigation', { name: '主导航' }).getByText('知识问答')).toBeVisible()
  await page.locator('textarea[name="question"]').fill('交付评审需要哪些材料？')
  await page.getByRole('button', { name: '提交问题' }).click()

  await expect(page.getByText('回答已降级')).toBeVisible()
  await expect(page.getByText('交付评审需要方案、风险清单和验收口径。', { exact: true })).toBeVisible()
  expect(idempotencyKeys).toHaveLength(1)
  expect(idempotencyKeys[0]).not.toBe('')
  const popupPromise = page.waitForEvent('popup')
  await page.locator('[data-source-action]').click()
  const popup = await popupPromise
  await expect(page.locator('[data-source-title]')).toHaveText('交付评审操作手册')
  await expect(popup).toHaveURL(/\/api\/v1\/documents\/document-1\/preview$/)
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 1366)
  await page.screenshot({ path: test.info().outputPath('qa-answered-degraded-1366.png'), fullPage: true })
  await page.setViewportSize({ width: 1600, height: 1000 })
  await expect(page.getByRole('heading', { name: '知识问答' })).toBeVisible()
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 1600)
  await page.screenshot({ path: test.info().outputPath('qa-answered-degraded-wide.png'), fullPage: true })
})

test('知识问答明确区分严格拒答与模型故障', async ({ page }) => {
  let attempts = 0
  await page.route(/\/api\/qa\/questions$/, (route) => {
    attempts += 1
    return route.fulfill({ json: attempts === 1 ? {
      requestId: 'qa-refused', status: 'REFUSED', answer: null,
      refusal: 'INSUFFICIENT_EVIDENCE', degraded: false, citations: [],
      completedAt: '2026-10-27T06:00:00Z',
    } : {
      requestId: 'qa-failed', status: 'FAILED', answer: null,
      refusal: null, degraded: false, citations: [], completedAt: '2026-10-27T06:01:00Z',
    } })
  })

  await page.goto('/qa')
  await page.locator('textarea[name="question"]').fill('没有依据的问题')
  await page.getByRole('button', { name: '提交问题' }).click()
  await expect(page.locator('[data-state="refused"]')).toContainText('依据不足，系统已严格拒答')

  await page.locator('textarea[name="question"]').fill('触发模型故障的问题')
  await page.getByRole('button', { name: '提交问题' }).click()
  await expect(page.locator('[data-state="failed"]')).toContainText('系统处理失败')
  await expect(page.locator('[data-state="failed"]')).not.toContainText('严格拒答')
})

test('来源撤权统一隐藏详情且移动宽度不产生横向滚动', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const longClaim = `https://knowledge.example.internal/${'unbroken-secure-identifier-'.repeat(18)}`
  await page.route(/\/api\/qa\/questions$/, (route) => route.fulfill({ json: {
    requestId: 'qa-revoked', status: 'ANSWERED', answer: longClaim,
    refusal: null, degraded: false,
    citations: [{ claimId: 'claim-revoked', text: longClaim, evidenceIds: ['evidence-revoked'] }],
    completedAt: '2026-10-27T06:00:00Z',
  } }))
  await page.route(/\/api\/qa\/questions\/qa-revoked\/sources\/evidence-revoked$/, (route) => route.fulfill({
    status: 404,
    json: apiError('绝密客户项目已经被撤销', 'NOT_FOUND'),
  }))

  await page.goto('/qa')
  await page.locator('textarea[name="question"]').fill('查看已撤权来源')
  await page.getByRole('button', { name: '提交问题' }).click()
  await page.locator('[data-source-action]').click()

  await expect(page.getByText('来源不存在或你已无权访问')).toBeVisible()
  await expect(page.getByText('绝密客户项目已经被撤销')).toHaveCount(0)
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 390)
  const columns = await page.locator('[data-citation-list]').evaluate((element) => getComputedStyle(element).gridTemplateColumns.split(' ').length)
  expect(columns).toBe(1)
  await page.screenshot({ path: test.info().outputPath('qa-source-revoked-mobile.png'), fullPage: true })
})
