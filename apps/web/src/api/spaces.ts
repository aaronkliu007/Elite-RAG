import { apiRequest } from './http'

export type SpaceRole = 'SPACE_ADMIN' | 'EDITOR' | 'REVIEWER_PUBLISHER' | 'READER'

export interface KnowledgeSpace {
  id: string
  name: string
  description: string
  revision: number
  createdBy: string
  createdAt: string
  allowedActions: string[]
}

export interface SpaceMember {
  userId: string
  username: string
  displayName: string
  enabled: boolean
  roles: SpaceRole[]
}

export interface MemberCandidate {
  userId: string
  username: string
  displayName: string
}

export interface PermissionGrant {
  id: string
  principalType: string
  principalId: string
  username: string | null
  displayName: string | null
  source: string
  expiresAt: string | null
}

export interface EffectiveUserPermission {
  userId: string
  username: string
  displayName: string
  enabled: boolean
  spaceReadable: boolean
  systemAdmin: boolean
  spaceRoles: SpaceRole[]
  grants: Array<{ grant: PermissionGrant, inherited: boolean, effective: boolean }>
}

function idempotencyHeaders(idempotencyKey: string) {
  return { 'Idempotency-Key': idempotencyKey }
}

export const spacesApi = {
  effectiveUserPermissions: (spaceId: string, userId: string) =>
    apiRequest<EffectiveUserPermission>(`/api/v1/knowledge-spaces/${encodeURIComponent(spaceId)}/users/${encodeURIComponent(userId)}/effective-permissions`),
  listPermissionGrants: (spaceId: string, page = 1) =>
    apiRequest<PermissionGrant[]>(`/api/v1/knowledge-spaces/${encodeURIComponent(spaceId)}/permission-grants?page=${page}&pageSize=100`),
  searchCandidates: (spaceId: string, query: string, page = 1) =>
    apiRequest<MemberCandidate[]>(`/api/v1/knowledge-spaces/${encodeURIComponent(spaceId)}/member-candidates?q=${encodeURIComponent(query)}&page=${page}&pageSize=20`),
  listSpaces: (page = 1, pageSize = 100) =>
    apiRequest<KnowledgeSpace[]>(`/api/v1/knowledge-spaces?page=${page}&pageSize=${pageSize}`),

  createSpace: (name: string, description: string, idempotencyKey: string) =>
    apiRequest<KnowledgeSpace>('/api/v1/knowledge-spaces', {
      method: 'POST',
      headers: idempotencyHeaders(idempotencyKey),
      body: JSON.stringify({ name, description }),
    }),

  listMembers: (spaceId: string, page = 1, pageSize = 100) =>
    apiRequest<SpaceMember[]>(
      `/api/v1/knowledge-spaces/${encodeURIComponent(spaceId)}/members?page=${page}&pageSize=${pageSize}`,
    ),

  grantRole: (spaceId: string, userId: string, role: SpaceRole, idempotencyKey: string) =>
    apiRequest<SpaceMember>(`/api/v1/knowledge-spaces/${encodeURIComponent(spaceId)}/members`, {
      method: 'POST',
      headers: idempotencyHeaders(idempotencyKey),
      body: JSON.stringify({ userId, role }),
    }),

  revokeRole: (
    spaceId: string,
    userId: string,
    role: SpaceRole,
    idempotencyKey: string,
  ) => apiRequest<SpaceMember>(
    `/api/v1/knowledge-spaces/${encodeURIComponent(spaceId)}/members/${encodeURIComponent(userId)}/roles/${encodeURIComponent(role)}`,
    {
      method: 'DELETE',
      headers: idempotencyHeaders(idempotencyKey),
    },
  ),
}
