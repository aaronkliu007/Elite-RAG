import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'

import { ApiRequestError, clearCsrfToken } from '../api/http'
import { adminApi } from '../api/admin'
import { dingtalkApi } from '../api/dingtalk'
import { createAppRouter } from '../router'
import { useSessionStore } from '../stores/session'
import KnowledgeHomeView from './KnowledgeHomeView.vue'
import UserAdminView from './UserAdminView.vue'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const alice = {
  id: '00000000-0000-7000-8000-000000000001',
  username: 'alice-with-a-very-long-unbroken-identifier-for-layout-verification',
  displayName: '华东大区工业互联网解决方案与交付协同中心负责人爱丽丝',
  enabled: true,
  createdAt: '2026-10-09T01:00:00Z',
  updatedAt: '2026-10-09T01:00:00Z',
}

describe('用户管理页', () => {
  beforeEach(() => {
    clearCsrfToken()
    vi.restoreAllMocks()
    vi.spyOn(dingtalkApi, 'status').mockResolvedValue({ configured: false, authoritativeScopeConfirmed: false })
    setActivePinia(createPinia())
  })

  it('支持加载超过一百人的用户列表，失败时保留已加载用户并重试', async () => {
    const firstPage = Array.from({ length: 100 }, (_, i) => ({ ...alice, id: `u${i}`, username: `user${i}`, displayName: `用户${i}` }))
    const list = vi.spyOn(adminApi, 'listUsers').mockResolvedValueOnce(firstPage).mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce([{ ...alice, id: 'later', username: 'later', displayName: '后续用户' }])
    const wrapper = mount(UserAdminView)
    await flushPromises()
    await wrapper.get('[data-action="more-users"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('用户0')
    expect(wrapper.get('[role="alert"]').text()).toContain('加载更多用户失败')
    await wrapper.get('[data-action="more-users"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('后续用户')
    expect(list.mock.calls.map(call => call[0])).toEqual([undefined, 2, 2])
    expect(wrapper.find('[data-action="more-users"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('取消重置密码后关闭表单并清空输入', async () => {
    vi.spyOn(adminApi, 'listUsers').mockResolvedValue([alice])
    const wrapper = mount(UserAdminView)
    await flushPromises()
    await wrapper.get('[data-action="show-reset"]').trigger('click')
    await wrapper.get('input[name="resetPassword"]').setValue('临时密码-123456789')
    await wrapper.get('[data-action="cancel-reset"]').trigger('click')
    expect(wrapper.find('input[name="resetPassword"]').exists()).toBe(false)
    await wrapper.get('[data-action="show-reset"]').trigger('click')
    expect((wrapper.get('input[name="resetPassword"]').element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })

  it('可复制用户 ID 用于空间成员授权，并明确提示结果', async () => {
    vi.spyOn(adminApi, 'listUsers').mockResolvedValue([alice])
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const wrapper = mount(UserAdminView)
    await flushPromises()
    await wrapper.get('[data-action="copy-user-id"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith(alice.id)
    expect(wrapper.get('[data-copy-status]').text()).toContain('已复制用户 ID')
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('刷新用户列表后忽略此前分页请求的迟到结果', async () => {
    const firstPage = Array.from({ length: 100 }, (_, i) => ({ ...alice, id: `u${i}`, username: `user${i}` }))
    let finish!: (value: typeof firstPage) => void
    const pending = new Promise<typeof firstPage>((resolve) => { finish = resolve })
    vi.spyOn(adminApi, 'listUsers').mockResolvedValueOnce(firstPage).mockReturnValueOnce(pending).mockResolvedValueOnce([{ ...alice, id: 'fresh', username: 'fresh', displayName: '刷新后用户' }])
    vi.spyOn(adminApi, 'setEnabled').mockRejectedValue(new ApiRequestError('状态冲突', 409, 'CONFLICT', '', {}))
    const wrapper = mount(UserAdminView)
    await flushPromises()
    await wrapper.get('[data-action="more-users"]').trigger('click')
    await wrapper.findAll('[data-action="toggle-user"]')[0]!.trigger('click')
    await flushPromises()
    finish([{ ...alice, id: 'stale', username: 'stale', displayName: '过期用户' }])
    await flushPromises()
    expect(wrapper.text()).toContain('刷新后用户')
    expect(wrapper.text()).not.toContain('过期用户')
    wrapper.unmount()
  })

  it('拒绝非系统管理员进入用户管理路由', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path === '/api/v1/session/csrf') {
        return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-admin' })
      }
      if (path === '/api/v1/session/me') {
        return json({ userId: alice.id, username: 'alice', displayName: '爱丽丝' })
      }
      if (path.startsWith('/api/v1/admin/users')) {
        return json({ code: 'FORBIDDEN', message: '无权管理用户', traceId: 'trace-admin', fieldErrors: {} }, 403)
      }
      if (path.startsWith('/api/v1/knowledge-spaces')) return json([])
      throw new Error(`unexpected request: ${path}`)
    }))
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createAppRouter(createMemoryHistory())

    await router.push('/admin/users')
    await router.isReady()

    expect(router.currentRoute.value.path).toBe('/')
  })

  it('知识空间不重复展示用户入口，并在创建冲突后刷新空间列表', async () => {
    const createdSpace = {
      id: '10000000-0000-7000-8000-000000000001',
      name: '研发知识空间',
      description: '研发制度和技术资产',
      revision: 1,
      createdBy: alice.id,
      createdAt: '2026-10-09T01:00:00Z',
      allowedActions: ['VIEW', 'MANAGE_ACL'],
    }
    let listCalls = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      const method = init?.method ?? 'GET'
      if (path === '/api/v1/admin/users?page=1&pageSize=1') return json([alice])
      if (path === '/api/v1/knowledge-spaces?page=1&pageSize=100' && method === 'GET') {
        listCalls += 1
        return json(listCalls === 1 ? [] : [createdSpace])
      }
      if (path === '/api/v1/session/csrf') {
        return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-space' })
      }
      if (path === '/api/v1/knowledge-spaces' && method === 'POST') {
        return json({ code: 'CONFLICT', message: '空间已存在', traceId: 'trace-space-conflict', fieldErrors: {} }, 409)
      }
      if (path.includes('/documents?page=1&pageSize=100')) return json([])
      throw new Error(`unexpected request: ${method} ${path}`)
    }))
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createAppRouter(createMemoryHistory())
    const wrapper = mount(KnowledgeHomeView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.find('a[href="/admin/users"]').exists()).toBe(false)
    expect(wrapper.get('[data-form="create-space"]').attributes('style')).toContain('display: none')
    await wrapper.get('button[data-action="open-create-space"]').trigger('click')
    expect(wrapper.get('button[data-action="open-create-space"]').attributes('aria-expanded')).toBe('true')
    await wrapper.get('input[name="spaceName"]').setValue('研发知识空间')
    await wrapper.get('textarea[name="spaceDescription"]').setValue('研发制度和技术资产')
    await wrapper.get('[data-form="create-space"]').trigger('submit')
    await flushPromises()

    expect(listCalls).toBe(2)
    expect(wrapper.get('[role="alert"]').text()).toContain('空间已存在')
    expect(wrapper.text()).toContain('研发知识空间')
  })

  it('呈现加载和长名称，并支持创建、启停及重置密码', async () => {
    let resolveUsers!: (response: Response) => void
    const usersResponse = new Promise<Response>((resolve) => { resolveUsers = resolve })
    let disableAttempts = 0
    const disableKeys: string[] = []
    const mutationKeys: string[] = []
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      const method = init?.method ?? 'GET'
      if (path === '/api/v1/session/csrf') {
        return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-user-write' })
      }
      if (path === '/api/v1/admin/users?page=1&pageSize=100' && method === 'GET') return usersResponse
      const key = new Headers(init?.headers).get('Idempotency-Key') ?? ''
      if (path === '/api/v1/admin/users' && method === 'POST') {
        mutationKeys.push(key)
        return json({ ...alice, id: '00000000-0000-7000-8000-000000000002', username: 'bob', displayName: '鲍勃' }, 201)
      }
      if (path.endsWith('/enabled') && method === 'PATCH') {
        disableKeys.push(key)
        disableAttempts += 1
        if (disableAttempts === 1) {
          return json({ code: 'SERVICE_UNAVAILABLE', message: '服务暂时不可用', traceId: 'trace-retry', fieldErrors: {} }, 503)
        }
        return json({ userId: alice.id, status: 'UPDATED' })
      }
      if (path.endsWith('/reset-password') && method === 'POST') {
        mutationKeys.push(key)
        return json({ userId: alice.id, status: 'PASSWORD_RESET' })
      }
      throw new Error(`unexpected request: ${method} ${path}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(UserAdminView)

    expect(wrapper.text()).toContain('正在加载用户')
    resolveUsers(json([alice]))
    await flushPromises()

    expect(wrapper.get('[data-user-name]').attributes('title')).toBe(alice.displayName)
    expect(wrapper.get('[data-user-username]').attributes('title')).toBe(alice.username)

    expect(wrapper.get('[data-form="create-user"]').attributes('style')).toContain('display: none')
    await wrapper.get('button[data-action="open-create-user"]').trigger('click')
    expect(wrapper.get('button[data-action="open-create-user"]').attributes('aria-expanded')).toBe('true')
    await wrapper.get('input[name="username"]').setValue('bob')
    await wrapper.get('input[name="displayName"]').setValue('鲍勃')
    await wrapper.get('input[name="password"]').setValue('long-password-123')
    await wrapper.get('[data-form="create-user"]').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('bob')

    await wrapper.get('[data-action="toggle-user"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('trace-retry')
    await wrapper.get('[data-action="toggle-user"]').trigger('click')
    await flushPromises()
    expect(disableKeys).toHaveLength(2)
    expect(disableKeys[0]).toBe(disableKeys[1])
    expect(disableKeys[0]).toMatch(/^[0-9a-f-]{36}$/)
    expect(wrapper.get('[data-action="toggle-user"]').text()).toContain('启用')

    await wrapper.get('[data-action="show-reset"]').trigger('click')
    await wrapper.get('input[name="resetPassword"]').setValue('another-password-123')
    await wrapper.get('[data-form="reset-password"]').trigger('submit')
    await flushPromises()

    expect(mutationKeys).toHaveLength(2)
    expect(new Set(mutationKeys).size).toBe(2)
    expect(wrapper.find('input[name="resetPassword"]').exists()).toBe(false)
  })

  it('为无数据和加载失败提供明确状态及重试入口', async () => {
    let attempt = 0
    vi.stubGlobal('fetch', vi.fn(async () => {
      attempt += 1
      if (attempt === 1) {
        return json({ code: 'SERVICE_UNAVAILABLE', message: '用户服务不可用', traceId: 'trace-users', fieldErrors: {} }, 503)
      }
      return json([])
    }))
    const wrapper = mount(UserAdminView)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('用户服务不可用')
    await wrapper.get('[data-action="retry-users"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('暂无用户')
  })

  it('不向当前登录用户提供停用自己的操作', async () => {
    useSessionStore().establish({ userId: alice.id, username: 'alice', displayName: '爱丽丝' })
    vi.stubGlobal('fetch', vi.fn(async () => json([alice])))

    const wrapper = mount(UserAdminView)
    await flushPromises()

    const toggle = wrapper.get('[data-action="toggle-user"]')
    expect(toggle.attributes('disabled')).toBeDefined()
    expect(toggle.attributes('title')).toBe('不能停用当前登录用户')
  })
})
