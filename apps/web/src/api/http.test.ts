import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useSessionStore } from '../stores/session'
import {
  ApiRequestError,
  apiRequest,
  clearCsrfToken,
  configureUnauthorizedHandler,
  loadCsrfToken,
} from './http'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('通用 HTTP 客户端', () => {
  beforeEach(() => {
    clearCsrfToken()
    configureUnauthorizedHandler(null)
    vi.restoreAllMocks()
  })

  it('写请求自动发送同源 Cookie、内存 CSRF 和唯一追踪 ID', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-memory-only',
      }))
      .mockResolvedValueOnce(json({ accepted: true }))
    vi.stubGlobal('fetch', fetchMock)

    await loadCsrfToken()
    await apiRequest('/api/v1/example', {
      method: 'POST',
      body: JSON.stringify({ name: '知识库' }),
    })

    const [, init] = fetchMock.mock.calls[1] as [string, RequestInit]
    const headers = new Headers(init.headers)
    expect(init.credentials).toBe('same-origin')
    expect(headers.get('X-CSRF-TOKEN')).toBe('csrf-memory-only')
    expect(headers.get('X-Request-Id')).toMatch(/^[0-9a-f-]{36}$/)
    expect(headers.get('Accept')).toBe('application/json')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(localStorage.getItem('csrf')).toBeNull()
  })

  it('任意 API 返回 401 时立即清空内存会话', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const session = useSessionStore()
    session.establish({
      userId: '00000000-0000-7000-8000-000000000001',
      username: 'alice',
      displayName: '爱丽丝',
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      code: 'UNAUTHORIZED',
      message: '请先登录',
      traceId: '018f25c8-5f7a-7abc-8def-0123456789ab',
      fieldErrors: {},
    }, 401)))

    await expect(apiRequest('/api/v1/knowledge-spaces')).rejects.toBeInstanceOf(ApiRequestError)

    expect(session.authenticated).toBe(false)
    expect(session.user).toBeNull()
  })

  it.each([
    [403, '无权执行此操作'],
    [409, '请求与当前状态冲突'],
    [503, '服务暂时不可用'],
  ])('保留 %i 中文错误消息和追踪 ID', async (status, message) => {
    const traceId = `018f25c8-5f7a-7abc-8def-012345678${status}`
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      code: 'TEST_ERROR',
      message,
      traceId,
      fieldErrors: {},
    }, status)))

    const failure = await apiRequest('/api/v1/example').catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiRequestError)
    expect((failure as ApiRequestError).message).toBe(message)
    expect((failure as ApiRequestError).traceId).toBe(traceId)
    expect((failure as ApiRequestError).displayMessage).toContain(message)
    expect((failure as ApiRequestError).displayMessage).toContain(traceId)
  })
})
