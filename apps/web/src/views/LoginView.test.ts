import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'

import { clearCsrfToken } from '../api/http'
import { createAppRouter } from '../router'
import LoginView from './LoginView.vue'
import ChangePasswordView from './ChangePasswordView.vue'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('登录页', () => {
  beforeEach(() => {
    clearCsrfToken()
    vi.restoreAllMocks()
  })

  it('初始密码登录后强制改密，完成后重新登录', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      calls.push(path)
      if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })
      if (path.endsWith('/login')) return json({ userId: 'u1', username: 'admin', displayName: '管理员', passwordChangeRequired: true })
      if (path.endsWith('/change-initial-password')) return new Response(null, { status: 204 })
      return json({ code: 'UNAUTHORIZED', message: '请先登录' }, 401)
    }))
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createAppRouter(createMemoryHistory())
    await router.push('/login?redirect=/upload')
    await router.isReady()
    const login = mount(LoginView, { global: { plugins: [pinia, router] } })
    await login.get('input[name="username"]').setValue('admin')
    await login.get('input[name="password"]').setValue('admin')
    await login.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/change-password')
    await router.push('/upload')
    expect(router.currentRoute.value.path).toBe('/change-password')
    const form = mount(ChangePasswordView, { global: { plugins: [pinia, router] } })
    const inputs = form.findAll('input')
    await inputs[0]!.setValue('admin')
    await inputs[1]!.setValue('new-password-2026')
    await inputs[2]!.setValue('new-password-2026')
    await form.get('form').trigger('submit')
    await flushPromises()
    expect(calls).toContain('/api/v1/session/change-initial-password')
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('无原路由时登录后进入工作台', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })
      if (path.endsWith('/login')) return json({ userId: 'u1', username: 'alice', displayName: '爱丽丝' })
      return json({ code: 'UNAUTHORIZED', message: '请先登录' }, 401)
    }))
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createAppRouter(createMemoryHistory())
    await router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } })
    await wrapper.get('input[name="username"]').setValue('alice')
    await wrapper.get('input[name="password"]').setValue('password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/workbench')
  })

  it('使用密码管理器语义、防重复提交并在成功后返回原路由', async () => {
    let finishLogin!: (response: Response) => void
    const loginResponse = new Promise<Response>((resolve) => { finishLogin = resolve })
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path === '/api/v1/session/csrf') {
        return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-login' })
      }
      if (path === '/api/v1/session/login') return loginResponse
      return json({
        code: 'UNAUTHORIZED',
        message: '请先登录',
        traceId: '018f25c8-5f7a-7abc-8def-0123456789ab',
        fieldErrors: {},
      }, 401)
    })
    vi.stubGlobal('fetch', fetchMock)
    const storageSpy = vi.spyOn(Storage.prototype, 'setItem')
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createAppRouter(createMemoryHistory())
    await router.push('/login?redirect=/upload')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } })

    const username = wrapper.get('input[name="username"]')
    const password = wrapper.get('input[name="password"]')
    expect(username.attributes('autocomplete')).toBe('username')
    expect(password.attributes('autocomplete')).toBe('current-password')
    await username.setValue('alice')
    await password.setValue('correct-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('form').trigger('submit')

    expect(fetchMock.mock.calls.filter(([path]) => String(path).endsWith('/login'))).toHaveLength(1)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()

    finishLogin(json({
      userId: '00000000-0000-7000-8000-000000000001',
      username: 'alice',
      displayName: '爱丽丝',
    }))
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/upload')
    expect(storageSpy).not.toHaveBeenCalled()
    expect((password.element as HTMLInputElement).value).toBe('')
  })

  it('展示中文失败原因和追踪 ID', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/v1/session/csrf') {
        return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-login' })
      }
      return json({
        code: 'SERVICE_UNAVAILABLE',
        message: '服务暂时不可用',
        traceId: '018f25c8-5f7a-7abc-8def-0123456789ab',
        fieldErrors: {},
      }, 503)
    }))
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createAppRouter(createMemoryHistory())
    await router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } })

    await wrapper.get('input[name="username"]').setValue('alice')
    await wrapper.get('input[name="password"]').setValue('correct-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('服务暂时不可用')
    expect(wrapper.get('[role="alert"]').text()).toContain('018f25c8-5f7a-7abc-8def-0123456789ab')
  })
})
