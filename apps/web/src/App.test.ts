import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'

import App from './App.vue'
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
