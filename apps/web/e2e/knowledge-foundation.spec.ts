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
