import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { spacesApi } from '../api/spaces'
import EffectiveUserPermissionsPanel from './EffectiveUserPermissionsPanel.vue'

afterEach(() => vi.restoreAllMocks())
it('选择用户后展示各空间有效状态及部门继承来源', async () => {
  vi.spyOn(spacesApi, 'effectiveUserPermissions').mockResolvedValue({ userId: 'u', username: 'zhang', displayName: '张三', enabled: true, spaceReadable: true, systemAdmin: false, spaceRoles: ['READER'], grants: [{ inherited: true, effective: true, grant: { id: 'g', principalType: 'DEPARTMENT', principalId: 'd', username: null, displayName: '研发部', source: 'DIRECT', expiresAt: null } }] })
  const wrapper = mount(EffectiveUserPermissionsPanel, { props: { spaceId: 'a', spaces: [{ id: 'a', name: '研发空间' }], users: [{ id: 'u', username: 'zhang', displayName: '张三', enabled: true, createdAt: '', updatedAt: '' }] } })
  await wrapper.get('select[name="effectiveUser"]').setValue('u')
  await flushPromises()
  expect(wrapper.text()).toContain('研发空间')
  expect(wrapper.text()).toContain('可访问')
  expect(wrapper.text()).toContain('研发部')
  expect(wrapper.text()).toContain('部门继承')
  wrapper.unmount()
})
