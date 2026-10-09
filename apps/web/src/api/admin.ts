import { ApiRequestError, apiRequest } from './http'

export interface AdminUser {
  id: string
  username: string
  displayName: string
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateUserInput {
  username: string
  displayName: string
  password: string
}

interface OperationResult {
  userId: string
  status: string
}

function idempotencyHeaders(idempotencyKey: string) {
  return { 'Idempotency-Key': idempotencyKey }
}

export const adminApi = {
  listUsers: (page = 1, pageSize = 100) =>
    apiRequest<AdminUser[]>(`/api/v1/admin/users?page=${page}&pageSize=${pageSize}`),

  async canManageUsers() {
    try {
      await apiRequest<AdminUser[]>('/api/v1/admin/users?page=1&pageSize=1')
      return true
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 403) return false
      throw error
    }
  },

  createUser: (input: CreateUserInput, idempotencyKey: string) =>
    apiRequest<AdminUser>('/api/v1/admin/users', {
      method: 'POST',
      headers: idempotencyHeaders(idempotencyKey),
      body: JSON.stringify(input),
    }),

  setEnabled: (userId: string, enabled: boolean, idempotencyKey: string) =>
    apiRequest<OperationResult>(`/api/v1/admin/users/${encodeURIComponent(userId)}/enabled`, {
      method: 'PATCH',
      headers: idempotencyHeaders(idempotencyKey),
      body: JSON.stringify({ enabled }),
    }),

  resetPassword: (userId: string, password: string, idempotencyKey: string) =>
    apiRequest<OperationResult>(`/api/v1/admin/users/${encodeURIComponent(userId)}/reset-password`, {
      method: 'POST',
      headers: idempotencyHeaders(idempotencyKey),
      body: JSON.stringify({ password }),
    }),
}
