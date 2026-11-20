import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { spacesApi } from '../api/spaces'
import PermissionSourcesPanel from './PermissionSourcesPanel.vue'

describe('空间授权来源', () => {
  afterEach(() => vi.restoreAllMocks())
  it('分别展示角色、直接和待确认来源，可按用户名筛选', async () => {
    vi.spyOn(spacesApi, 'listPermissionGrants').mockResolvedValue([
      { id: '1', principalType: 'USER', principalId: 'u', username: 'zhang', displayName: '张三', source: 'DIRECT', expiresAt: null },
      { id: '2', principalType: 'USER', principalId: 'u', username: 'zhang', displayName: '张三', source: 'SPACE_ROLE', expiresAt: null },
      { id: '3', principalType: 'USER', principalId: 'v', username: 'li', displayName: '李四', source: 'LEGACY_UNKNOWN', expiresAt: null },
    ])
    const wrapper = mount(PermissionSourcesPanel, { props: { spaceId: 'space' } })
    await flushPromises()
    expect(wrapper.text()).toContain('直接授权')
    expect(wrapper.text()).toContain('空间角色')
    expect(wrapper.text()).toContain('历史来源待确认')
    await wrapper.get('input[name="permissionQuery"]').setValue('张三')
    expect(wrapper.text()).not.toContain('李四')
    wrapper.unmount()
  })
  it('按用户查看时覆盖所有可管理空间', async () => {
    vi.spyOn(spacesApi, 'listPermissionGrants').mockResolvedValue([])
    vi.spyOn(spacesApi, 'effectiveUserPermissions').mockResolvedValue({ userId: 'u', username: 'zhang', displayName: '张三', enabled: true, spaceReadable: true, systemAdmin: false, spaceRoles: [], grants: [] })
    const wrapper = mount(PermissionSourcesPanel, { props: { spaceId: 'a', spaces: [{ id: 'a', name: '研发' }, { id: 'b', name: '交付' }], users: [{ id: 'u', username: 'zhang', displayName: '张三', enabled: true, createdAt: '', updatedAt: '' }] } })
    await flushPromises()
    await wrapper.get('select[name="overviewMode"]').setValue('user')
    await flushPromises()
    await wrapper.get('select[name="effectiveUser"]').setValue('u')
    await flushPromises()
    expect(wrapper.text()).toContain('研发')
    expect(wrapper.text()).toContain('交付')
    wrapper.unmount()
  })
})
