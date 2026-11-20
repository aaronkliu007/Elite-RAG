import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import App from './App.vue'
import { adminApi } from './api/admin'
import { useSessionStore } from './stores/session'
import { clearCsrfToken } from './api/http'
import { createAppRouter } from './router'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('认证应用骨架', () => {
  beforeEach(() => {
    clearCsrfToken()
    vi.restoreAllMocks()
  })

  it('空间方案列表只高亮方案工作台，不误高亮知识空间', async () => {
    vi.spyOn(adminApi, 'canManageUsers').mockResolvedValue(false)
    const pinia = createPinia(); setActivePinia(pinia)
    useSessionStore().establish({ userId: 'u', username: 'u', displayName: '测试用户' })
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/spaces/:spaceId/solutions', component: { template: '<div />' } },
      { path: '/', component: { template: '<div />' } },
    ] })
    await router.push('/spaces/a/solutions'); await router.isReady()
    const wrapper = shallowMount(App, { global: { plugins: [pinia, router] } })
    expect(wrapper.get('router-link-stub[to="/"]').classes()).not.toContain('current-section')
    expect(wrapper.get('router-link-stub[to="/solutions"]').classes()).toContain('current-section')
    wrapper.unmount()
  })

  it('启动时先加载 CSRF 和当前会话，并显示真实用户与退出操作', async () => {
    const paths: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      paths.push(path)
      if (path === '/api/v1/session/csrf') {
        return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-1' })
      }
      return json({
        userId: '00000000-0000-7000-8000-000000000001',
        username: 'alice',
        displayName: '爱丽丝',
      })
    }))
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createAppRouter(createMemoryHistory())

    await router.push('/')
    await router.isReady()
    const wrapper = shallowMount(App, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(paths.slice(0, 2)).toEqual(['/api/v1/session/csrf', '/api/v1/session/me'])
    expect(wrapper.text()).toContain('爱丽丝')
    expect(wrapper.find('router-link-stub[to="/qa"]').exists()).toBe(true)
    expect(wrapper.get('button[data-action="logout"]').text()).toBe('退出登录')
    expect(wrapper.text()).not.toContain('钉钉')
    wrapper.unmount()
  })

  it('管理员可在主导航中找到用户管理和问答评测', async () => {
    vi.spyOn(adminApi, 'canManageUsers').mockResolvedValue(true)
    const pinia = createPinia()
    setActivePinia(pinia)
    useSessionStore().establish({ userId: 'admin', username: 'admin', displayName: '管理员' })
    const router = createAppRouter(createMemoryHistory())
    await router.push('/')
    await router.isReady()
    const wrapper = shallowMount(App, { global: { plugins: [pinia, router] } })
    await flushPromises()
    expect(wrapper.find('router-link-stub[to="/admin/users"]').exists()).toBe(true)
    expect(wrapper.find('router-link-stub[to="/admin/evaluations"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('权限检查失败时显示可重试提示，成功后显示管理员入口', async () => {
    vi.spyOn(adminApi, 'canManageUsers').mockRejectedValueOnce(new Error('unavailable')).mockResolvedValue(true)
    const pinia = createPinia()
    setActivePinia(pinia)
    useSessionStore().establish({ userId: 'admin', username: 'admin', displayName: '管理员' })
    const router = createAppRouter(createMemoryHistory())
    await router.push('/')
    await router.isReady()
    const wrapper = shallowMount(App, { global: { plugins: [pinia, router] } })
    await flushPromises()
    expect(wrapper.find('router-link-stub[to="/admin/users"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('管理权限暂时无法确认')
    await wrapper.get('button[data-action="retry-admin-capability"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('router-link-stub[to="/admin/users"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('未认证访问受保护页面时跳转登录并保留原路由', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/v1/session/csrf') {
        return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-2' })
      }
      return json({
        code: 'UNAUTHORIZED',
        message: '请先登录',
        traceId: '018f25c8-5f7a-7abc-8def-0123456789ab',
        fieldErrors: {},
      }, 401)
    }))
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createAppRouter(createMemoryHistory())

    await router.push('/upload')
    await router.isReady()

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/upload')
  })
})
