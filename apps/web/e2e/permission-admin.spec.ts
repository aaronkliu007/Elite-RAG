import { expect, test } from '@playwright/test'

test('集中配置空间成员角色并适配桌面宽度', async ({ page }) => {
  await page.route('**/api/v1/session/csrf', route => route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'test' } }))
  await page.route('**/api/v1/session/me', route => route.fulfill({ json: { userId: 'admin', username: 'admin', displayName: '管理员' } }))
  await page.route('**/api/v1/admin/users?*', route => route.fulfill({ json: [{ id: 'u1', username: 'alice', displayName: '研发部张三', enabled: true, createdAt: '', updatedAt: '' }] }))
  await page.route('**/api/v1/knowledge-spaces?*', route => route.fulfill({ json: [{ id: 's1', name: '研发知识空间', description: '', revision: 1, createdBy: 'admin', createdAt: '', allowedActions: ['MANAGE_ACL'] }] }))
  await page.route('**/api/v1/knowledge-spaces/s1/members?*', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/knowledge-spaces/s1/permission-grants?*', route => route.fulfill({ json: [{ id: 'g1', principalType: 'USER', principalId: 'u1', username: 'alice', displayName: '研发部张三', source: 'LEGACY_UNKNOWN', expiresAt: null }] }))
  await page.route('**/api/v1/knowledge-spaces/s1/users/u1/effective-permissions', route => route.fulfill({ json: { userId: 'u1', username: 'alice', displayName: '研发部张三', enabled: true, spaceReadable: true, systemAdmin: false, spaceRoles: ['READER'], grants: [{ inherited: true, effective: true, grant: { id: 'g2', principalType: 'DEPARTMENT', principalId: 'd1', username: null, displayName: '研发部', source: 'DIRECT', expiresAt: null } }] } }))
  await page.route('**/api/v1/knowledge-spaces/s1/members', route => route.fulfill({ json: { userId: 'u1', username: 'alice', displayName: '研发部张三', enabled: true, roles: ['READER'] } }))
  for (const width of [1920, 1366]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/admin/permissions')
    await expect(page.getByRole('combobox', { name: '知识空间', exact: true })).toHaveValue('s1')
    await expect(page.getByText('历史来源待确认', { exact: true })).toBeVisible()
    await page.getByRole('combobox', { name: '用户', exact: true }).selectOption('u1')
    await page.getByRole('button', { name: '授予角色' }).click()
    await expect(page.getByRole('region', { name: '成员与角色管理' }).getByText('研发部张三', { exact: true })).toBeVisible()
    await page.getByRole('combobox', { name: '查看方式' }).selectOption('user')
    await page.getByRole('combobox', { name: '选择用户' }).selectOption('u1')
    await expect(page.getByText('部门继承：研发部 · 生效')).toBeVisible()
    await page.screenshot({ path: `test-results/effective-permissions-${width}.png` })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  }
})
