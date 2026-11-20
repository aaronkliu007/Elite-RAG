import { expect, test } from '@playwright/test'

test('用户管理显示钉钉同步状态并可预览', async ({ page }) => {
  await page.route('**/api/v1/session/csrf', route => route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'test' } }))
  await page.route('**/api/v1/session/me', route => route.fulfill({ json: { userId: 'admin', username: 'admin', displayName: '管理员' } }))
  await page.route('**/api/v1/admin/users?*', route => route.fulfill({ json: [{ id: 'u1', username: 'alice', displayName: '张三', enabled: true, createdAt: '', updatedAt: '' }] }))
  await page.route('**/api/v1/admin/dingtalk', route => route.fulfill({ json: { configured: true, authoritativeScopeConfirmed: false } }))
  await page.route('**/api/v1/admin/dingtalk/preview', route => route.fulfill({ json: { departmentCount: 3, userCount: 15, capturedAt: '2026-11-20T00:00:00Z' } }))
  for (const width of [1920, 1366]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/admin/users')
    await page.getByRole('button', { name: '预览通讯录' }).click()
    await expect(page.getByText('3 个部门、15 位成员 · 预览结果')).toBeVisible()
    await expect(page.getByRole('button', { name: '同步通讯录' })).toBeDisabled()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.screenshot({ path: `test-results/dingtalk-${width}.png` })
  }
})
