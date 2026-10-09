import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { clearCsrfToken } from '../api/http'
import SpaceMemberPanel from './SpaceMemberPanel.vue'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const spaceId = '10000000-0000-7000-8000-000000000001'
const member = {
  userId: '00000000-0000-7000-8000-000000000001',
  username: 'alice-with-a-very-long-unbroken-identifier-for-layout-verification',
  displayName: '华东大区工业互联网解决方案与交付协同中心负责人爱丽丝',
  enabled: true,
  roles: ['EDITOR'],
}

describe('空间成员管理面板', () => {
  beforeEach(() => {
    clearCsrfToken()
    vi.restoreAllMocks()
  })

  it('清楚呈现加载、空数据和失败重试状态', async () => {
    let resolveMembers!: (response: Response) => void
    const first = new Promise<Response>((resolve) => { resolveMembers = resolve })
    let attempt = 0
    vi.stubGlobal('fetch', vi.fn(async () => {
      attempt += 1
      if (attempt === 1) return first
      if (attempt === 2) {
        return json({ code: 'SERVICE_UNAVAILABLE', message: '成员服务不可用', traceId: 'trace-members', fieldErrors: {} }, 503)
      }
      return json([])
    }))
    const wrapper = mount(SpaceMemberPanel, { props: { spaceId } })

    expect(wrapper.text()).toContain('正在加载成员')
    resolveMembers(json([]))
    await flushPromises()
    expect(wrapper.text()).toContain('暂无空间成员')

    await wrapper.get('[data-action="reload-members"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('成员服务不可用')
    await wrapper.get('[data-action="retry-members"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('暂无空间成员')
  })

  it('角色只显示固定中文标签，写入重试沿用幂等键，冲突后刷新', async () => {
    let listCalls = 0
    let grantAttempts = 0
    const grantKeys: string[] = []
    const revokeKeys: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      const method = init?.method ?? 'GET'
      if (path === '/api/v1/session/csrf') {
        return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-member' })
      }
      if (path.endsWith('/members?page=1&pageSize=100') && method === 'GET') {
        listCalls += 1
        return json([member])
      }
      const key = new Headers(init?.headers).get('Idempotency-Key') ?? ''
      if (path.endsWith('/members') && method === 'POST') {
        grantKeys.push(key)
        grantAttempts += 1
        if (grantAttempts === 1) {
          return json({ code: 'SERVICE_UNAVAILABLE', message: '服务暂时不可用', traceId: 'trace-grant', fieldErrors: {} }, 503)
        }
        return json({ ...member, userId: '00000000-0000-7000-8000-000000000002', username: 'bob', displayName: '鲍勃', roles: ['READER'] })
      }
      if (path.includes('/roles/EDITOR') && method === 'DELETE') {
        revokeKeys.push(key)
        return json({ code: 'CONFLICT', message: '成员角色已发生变化', traceId: 'trace-conflict', fieldErrors: {} }, 409)
      }
      throw new Error(`unexpected request: ${method} ${path}`)
    }))
    const wrapper = mount(SpaceMemberPanel, { props: { spaceId } })
    await flushPromises()

    expect(wrapper.get('[data-member-name]').attributes('title')).toBe(member.displayName)
    expect(wrapper.get('[data-member-username]').attributes('title')).toBe(member.username)
    expect(wrapper.text()).toContain('编辑者')
    expect(wrapper.text()).not.toContain('EDITOR')

    await wrapper.get('input[name="memberUserId"]').setValue('00000000-0000-7000-8000-000000000002')
    await wrapper.get('select[name="memberRole"]').setValue('READER')
    await wrapper.get('[data-form="grant-member"]').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('trace-grant')
    await wrapper.get('[data-form="grant-member"]').trigger('submit')
    await flushPromises()

    expect(grantKeys).toHaveLength(2)
    expect(grantKeys[0]).toBe(grantKeys[1])
    expect(grantKeys[0]).toMatch(/^[0-9a-f-]{36}$/)
    expect(wrapper.text()).toContain('只读成员')

    await wrapper.get('[data-action="revoke-role"][data-role="EDITOR"]').trigger('click')
    await flushPromises()

    expect(revokeKeys[0]).toMatch(/^[0-9a-f-]{36}$/)
    expect(listCalls).toBe(2)
    expect(wrapper.get('[role="alert"]').text()).toContain('成员角色已发生变化')
  })
})
