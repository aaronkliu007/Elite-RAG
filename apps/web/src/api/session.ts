import { apiRequest, loadCsrfToken } from './http'

export interface SessionUser {
  userId: string
  username: string
  displayName: string
  passwordChangeRequired?: boolean
}

export const sessionApi = {
  csrf: () => loadCsrfToken(),
  current: () => apiRequest<SessionUser>('/api/v1/session/me'),
  login: (username: string, password: string) => apiRequest<SessionUser>(
    '/api/v1/session/login',
    {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    },
  ),
  logout: () => apiRequest<void>('/api/v1/session/logout', { method: 'POST' }),
  changeInitialPassword: (currentPassword: string, newPassword: string) => apiRequest<void>(
    '/api/v1/session/change-initial-password', { method: 'POST', body: JSON.stringify({ currentPassword, newPassword }) },
  ),
}
