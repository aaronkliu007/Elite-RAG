import { expect, test } from '@playwright/test'

test('初始密码登录只能进入改密页面，完成后重新登录', async ({ page }) => {
  let loggedIn = false
  await page.route('**/api/v1/session/csrf', (route) => route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'test-csrf' } }))
  await page.route('**/api/v1/session/me', (route) => route.fulfill(loggedIn
    ? { json: { userId: 'admin-id', username: 'admin', displayName: '系统管理员', passwordChangeRequired: true } }
    : { status: 401, json: { code: 'UNAUTHORIZED', message: '请先登录' } }))
  await page.route('**/api/v1/session/login', (route) => {
    loggedIn = true
    return route.fulfill({ json: { userId: 'admin-id', username: 'admin', displayName: '系统管理员', passwordChangeRequired: true } })
  })
  await page.route('**/api/v1/session/change-initial-password', (route) => {
    loggedIn = false
    return route.fulfill({ status: 204 })
  })
  for (const width of [1366, 390]) {
    loggedIn = false
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/login')
    await page.locator('input[name="username"]').fill('admin')
    await page.locator('input[name="password"]').fill('admin')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/change-password$/)
    await expect(page.getByRole('navigation', { name: '主导航' })).toHaveCount(0)
    await page.goto('/upload')
    await expect(page).toHaveURL(/\/change-password$/)
    await page.getByLabel('当前密码').fill('admin')
    await page.getByLabel('新密码', { exact: true }).fill('new-password-2026')
    await page.getByLabel('确认新密码').fill('new-password-2026')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.screenshot({ path: test.info().outputPath(`initial-password-${width}.png`) })
    await page.getByRole('button', { name: '修改密码' }).click()
    await expect(page).toHaveURL(/\/login\?passwordChanged=1$/)
    await expect(page.getByRole('status')).toContainText('密码已修改')
  }
})
