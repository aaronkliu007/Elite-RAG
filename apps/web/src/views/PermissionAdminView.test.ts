import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { adminApi } from '../api/admin'
import { ApiRequestError } from '../api/http'
import { spacesApi } from '../api/spaces'
import PermissionAdminView from './PermissionAdminView.vue'

describe('权限管理', () => {
  afterEach(() => vi.restoreAllMocks())
  it('加载全部分页的空间和用户，切换空间并按姓名选择用户授权', async () => {
    const space = (id: string) => ({ id, name: `空间${id}`, description: '', revision: 1, createdBy: 'u', createdAt: '', allowedActions: ['MANAGE_ACL'] })
    const user = (id: string) => ({ id, username: `u${id}`, displayName: `用户${id}`, enabled: true, createdAt: '', updatedAt: '' })
    vi.spyOn(spacesApi, 'listSpaces').mockResolvedValueOnce(Array.from({ length: 100 }, (_, i) => space(String(i)))).mockResolvedValueOnce([space('more')])
    vi.spyOn(adminApi, 'listUsers').mockResolvedValueOnce(Array.from({ length: 100 }, (_, i) => user(String(i)))).mockResolvedValueOnce([user('more')])
    const members = vi.spyOn(spacesApi, 'listMembers').mockResolvedValue([])
    const grant = vi.spyOn(spacesApi, 'grantRole').mockResolvedValue({ userId: 'more', username: 'umore', displayName: '用户more', enabled: true, roles: ['EDITOR'] })
    const wrapper = mount(PermissionAdminView)
    await flushPromises()
    await wrapper.get('select[name="permissionSpace"]').setValue('more')
    await flushPromises()
    expect(members).toHaveBeenLastCalledWith('more')
    await wrapper.get('select[name="memberUserId"]').setValue('more')
    await wrapper.get('select[name="memberRole"]').setValue('EDITOR')
    await wrapper.get('[data-form="grant-member"]').trigger('submit')
    await flushPromises()
    expect(grant).toHaveBeenCalledWith('more', 'more', 'EDITOR', expect.any(String))
    expect(wrapper.text()).toContain('用户more')
    wrapper.unmount()
  })
  it('空间管理员无用户列表权限时仍可管理授权空间', async () => {
    vi.spyOn(spacesApi, 'listSpaces').mockResolvedValue([{ id: 'a', name: '研发空间', description: '', revision: 1, createdBy: 'u', createdAt: '', allowedActions: ['MANAGE_ACL'] }])
    vi.spyOn(adminApi, 'listUsers').mockRejectedValue(new ApiRequestError('无权访问', 403, 'FORBIDDEN', '', {}))
    vi.spyOn(spacesApi, 'listMembers').mockResolvedValue([])
    const search = vi.spyOn(spacesApi, 'searchCandidates').mockResolvedValue([{ userId: 'u1', username: 'zhang', displayName: '张三' }])
    const wrapper = mount(PermissionAdminView)
    await flushPromises()
    expect(wrapper.find('[name="permissionSpace"]').exists()).toBe(true)
    await wrapper.get('input[name="memberSearch"]').setValue('张三')
    await wrapper.get('[data-action="search-candidates"]').trigger('click')
    await flushPromises()
    expect(search).toHaveBeenCalledWith('a', '张三', 1)
    expect(wrapper.get('select[name="memberUserId"]').text()).toContain('张三')
    expect(wrapper.find('input[name="memberUserId"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
