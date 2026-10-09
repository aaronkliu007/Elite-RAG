import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import SpaceDetailView from './SpaceDetailView.vue'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const longSpace = {
  id: '10000000-0000-7000-8000-000000000001',
  name: '华东区域制造行业超长知识资产协作空间名称用于验证省略与完整提示',
  description: 'https://knowledge.example.internal/a-very-long-unbroken-description-token-for-layout-verification',
  revision: 1,
  createdBy: '00000000-0000-7000-8000-000000000001',
  createdAt: '2026-10-09T01:00:00Z',
  allowedActions: ['VIEW', 'MANAGE_ACL'],
}

async function mountAtSpace() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/spaces/:spaceId', component: SpaceDetailView }],
  })
  await router.push(`/spaces/${longSpace.id}`)
  await router.isReady()
  return mount(SpaceDetailView, { global: { plugins: [router] } })
}

describe('知识空间详情页', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('空间管理员看到成员管理，且超长名称可查看完整值', async () => {
    const paths: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      paths.push(path)
      if (path === '/api/v1/knowledge-spaces?page=1&pageSize=100') return json([longSpace])
      if (path.endsWith('/members?page=1&pageSize=100')) return json([])
      throw new Error(`unexpected request: ${path}`)
    }))

    const wrapper = await mountAtSpace()
    await flushPromises()

    expect(wrapper.get('[data-space-name]').attributes('title')).toBe(longSpace.name)
    expect(wrapper.get('[data-space-description]').attributes('title')).toBe(longSpace.description)
    expect(wrapper.text()).toContain('成员与角色管理')
    expect(paths.some((path) => path.includes('/members?'))).toBe(true)
  })

  it('普通成员仅看到只读说明，不请求受限成员接口', async () => {
    const paths: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      paths.push(path)
      return json([{ ...longSpace, allowedActions: ['VIEW', 'SEARCH', 'DOWNLOAD'] }])
    }))

    const wrapper = await mountAtSpace()
    await flushPromises()

    expect(wrapper.text()).toContain('当前为只读访问')
    expect(wrapper.find('[data-form="grant-member"]').exists()).toBe(false)
    expect(paths.some((path) => path.includes('/members?'))).toBe(false)
  })

  it('加载失败时显示中文原因和重试操作', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({
      code: 'SERVICE_UNAVAILABLE',
      message: '空间服务暂时不可用',
      traceId: 'trace-space',
      fieldErrors: {},
    }, 503)))

    const wrapper = await mountAtSpace()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('空间服务暂时不可用')
    expect(wrapper.get('[role="alert"]').text()).toContain('trace-space')
    expect(wrapper.find('[data-action="retry-space"]').exists()).toBe(true)
  })
})
