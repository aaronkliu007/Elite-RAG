import { expect, test, type Page } from '@playwright/test'

const space = {
  id: 'space-1',
  name: '解决方案与交付知识空间',
  description: '公司级方案、评审记录与交付模板',
  documentCount: 1,
  inReviewCount: 1,
}

async function mockHome(page: Page, documents: unknown[]) {
  await page.route(/\/api\/v1\/knowledge-spaces$/, (route) => route.fulfill({ json: [space] }))
  await page.route(/\/api\/v1\/knowledge-spaces\/space-1\/documents$/, (route) =>
    route.fulfill({ json: documents }),
  )
}

test('未授权用户无法从列表或直链发现文档', async ({ page }) => {
  await mockHome(page, [])
  await page.route(/\/api\/v1\/knowledge-spaces\/documents\/secret-document$/, (route) =>
    route.fulfill({ status: 404, json: { title: 'Not Found', status: 404 } }),
  )

  await page.goto('/documents/secret-document')
  await expect(page.getByText('无法查看此文档', { exact: true })).toBeVisible()
  await expect(page.getByText('资源不存在或无权访问')).toBeVisible()
  await expect(page.getByText('绝密客户并购方案')).toHaveCount(0)

  await page.goto('/')
  await expect(page.getByText('当前空间暂无可见文档')).toBeVisible()
  await expect(page.getByText('绝密客户并购方案')).toHaveCount(0)
})

test('1366px 下长内容不遮挡主操作且发布门禁可见', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 })
  const longTitle = `某大型制造集团跨区域一体化智能运维与数据治理总体解决方案${'超长标题'.repeat(10)}`
  await page.route(/\/api\/v1\/knowledge-spaces\/documents\/document-1$/, (route) =>
    route.fulfill({
      json: {
        id: 'document-1-00000000-0000-0000-0000-000000000000',
        spaceId: 'space-1',
        title: longTitle,
        ownerName: '解决方案中心华东大区制造行业联合交付与质量保障专项工作组',
        classification: '内部',
        metadataComplete: true,
        parseStatus: 'SUCCEEDED',
        lifecycleStatus: 'IN_REVIEW',
        parseProgress: 100,
        permissions: { canEdit: true, canPublish: true, canManageAcl: true },
        parseErrors: [],
        preview: '第一章 项目背景\n本方案用于验证长内容展示、表格结构和人工审核流程。',
        permissionScope: '解决方案中心、华东大区及制造行业项目组的所有正式成员',
        versions: [
          {
            id: 'version-1-00000000-0000-0000-0000-000000000000',
            number: 1,
            status: 'IN_REVIEW',
            parseStatus: 'SUCCEEDED',
            createdAt: '2026-09-24T10:00:00Z',
            createdByName: '非常长的创建人姓名与所属团队名称用于布局压力测试',
          },
        ],
      },
    }),
  )

  await page.goto('/documents/document-1')
  const publish = page.getByTestId('publish-button')
  await expect(page.getByRole('heading', { name: longTitle })).toBeVisible()
  await expect(publish).toBeVisible()
  await expect(publish).toBeEnabled()
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 1366)
  await page.screenshot({ path: test.info().outputPath('document-review-1366.png'), fullPage: true })

  await page.setViewportSize({ width: 1600, height: 1000 })
  await expect(publish).toBeVisible()
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 1600)
  await page.screenshot({ path: test.info().outputPath('document-review-wide.png'), fullPage: true })
})

test('知识空间首页和上传页在 1366px 下无页面级横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 })
  await mockHome(page, [
    {
      id: 'document-1',
      title: `制造行业智能运维知识资产${'长标题'.repeat(12)}`,
      ownerName: '行业产品中心与华东交付中心联合工作组',
      lifecycleStatus: 'IN_REVIEW',
      parseStatus: 'SUCCEEDED',
      updatedAt: '2026-09-24T10:00:00Z',
    },
  ])

  await page.goto('/')
  await expect(page.getByRole('heading', { name: '知识空间', exact: true })).toBeVisible()
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 1366)
  await page.screenshot({ path: test.info().outputPath('knowledge-home-1366.png'), fullPage: true })

  await page.goto('/upload')
  await expect(page.getByRole('heading', { name: '上传新文档' })).toBeVisible()
  await expect(page.getByTestId('upload-submit')).toBeDisabled()
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 1366)
  await page.screenshot({ path: test.info().outputPath('upload-1366.png'), fullPage: true })
})
