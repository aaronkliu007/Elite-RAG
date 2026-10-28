export interface ApiErrorEnvelope {
  code: string
  message: string
  traceId: string
  fieldErrors: Record<string, string>
}

interface CsrfToken {
  headerName: string
  parameterName: string
  token: string
}

type UnauthorizedHandler = (() => void) | null

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])
let csrfToken: CsrfToken | null = null
let csrfRequest: Promise<CsrfToken> | null = null
let unauthorizedHandler: UnauthorizedHandler = null

export class ApiRequestError extends Error {
  readonly displayMessage: string

  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
    readonly traceId: string,
    readonly fieldErrors: Record<string, string>,
  ) {
    super(message)
    this.name = 'ApiRequestError'
    this.displayMessage = traceId ? `${message}（追踪 ID：${traceId}）` : message
  }
}

export function configureUnauthorizedHandler(handler: UnauthorizedHandler) {
  unauthorizedHandler = handler
}

export function clearCsrfToken() {
  csrfToken = null
  csrfRequest = null
}

export async function loadCsrfToken(): Promise<void> {
  if (!csrfToken) {
    csrfRequest ??= request<CsrfToken>('/api/v1/session/csrf', { method: 'GET' }, false)
    try {
      csrfToken = await csrfRequest
    } finally {
      csrfRequest = null
    }
  }
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  return request<T>(path, init, true)
}

export async function apiRequestBlob(path: string): Promise<Blob> {
  return request<Blob>(path, { method: 'GET' }, true, true)
}

async function request<T>(path: string, init: RequestInit, attachCsrf: boolean, blob = false): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  if (attachCsrf && MUTATING_METHODS.has(method)) {
    await loadCsrfToken()
  }

  const headers = new Headers(init.headers)
  headers.set('Accept', blob
    ? 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
    : 'application/json')
  headers.set('X-Request-Id', crypto.randomUUID())
  if (typeof init.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (attachCsrf && MUTATING_METHODS.has(method) && csrfToken) {
    headers.set(csrfToken.headerName, csrfToken.token)
  }

  const response = await fetch(path, {
    ...init,
    method,
    credentials: 'same-origin',
    headers,
  })
  if (!response.ok) {
    const failure = await errorFrom(response)
    if (response.status === 401) {
      clearCsrfToken()
      unauthorizedHandler?.()
    }
    throw failure
  }
  if (response.status === 204) {
    return undefined as T
  }
  if (blob) return (await response.blob()) as T
  return (await response.json()) as T
}

async function errorFrom(response: Response): Promise<ApiRequestError> {
  let envelope: Partial<ApiErrorEnvelope> = {}
  try {
    envelope = await response.json() as Partial<ApiErrorEnvelope>
  } catch {
    // The stable fallback below is used when a proxy returns a non-JSON failure.
  }
  const message = envelope.message ?? fallbackMessage(response.status)
  return new ApiRequestError(
    message,
    response.status,
    envelope.code ?? 'REQUEST_FAILED',
    envelope.traceId ?? response.headers.get('X-Request-Id') ?? '',
    envelope.fieldErrors ?? {},
  )
}

function fallbackMessage(status: number) {
  if (status === 401) return '请先登录'
  if (status === 403) return '无权执行此操作'
  if (status === 404) return '资源不存在或无权访问'
  if (status === 409) return '请求与当前状态冲突'
  if (status === 503) return '服务暂时不可用'
  return '请求处理失败，请稍后重试'
}
