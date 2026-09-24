export type ParseStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
export type LifecycleStatus = 'DRAFT' | 'IN_REVIEW' | 'PUBLISHED' | 'ARCHIVED'

export interface DocumentPermissions {
  canEdit: boolean
  canPublish: boolean
  canManageAcl: boolean
}

export interface ParseError {
  code: 'UNSUPPORTED' | 'ENCRYPTED' | 'LIMIT_EXCEEDED' | 'CONVERSION_FAILED' | 'TIMEOUT'
  message: string
}

export interface DocumentVersion {
  id: string
  number: number
  status: LifecycleStatus
  parseStatus: ParseStatus
  createdAt: string
  createdByName: string
}

export interface KnowledgeDocument {
  id: string
  spaceId: string
  title: string
  ownerName: string
  classification: string
  metadataComplete: boolean
  parseStatus: ParseStatus
  lifecycleStatus: LifecycleStatus
  parseProgress: number
  permissions: DocumentPermissions
  parseErrors: ParseError[]
  preview: string
  permissionScope: string
  versions: DocumentVersion[]
}

export interface KnowledgeSpaceSummary {
  id: string
  name: string
  description: string
  documentCount: number
  inReviewCount: number
}

export interface KnowledgeDocumentSummary {
  id: string
  title: string
  ownerName: string
  lifecycleStatus: LifecycleStatus
  parseStatus: ParseStatus
  updatedAt: string
}

export class KnowledgeApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message)
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json', ...init?.headers },
    ...init,
  })
  if (!response.ok) {
    throw new KnowledgeApiError(response.status === 404 ? '资源不存在或无权访问' : '知识服务请求失败', response.status)
  }
  return (await response.json()) as T
}

export const knowledgeApi = {
  listSpaces: () => request<KnowledgeSpaceSummary[]>('/api/v1/knowledge-spaces'),
  listDocuments: (spaceId: string) =>
    request<KnowledgeDocumentSummary[]>(
      `/api/v1/knowledge-spaces/${encodeURIComponent(spaceId)}/documents`,
    ),
  getDocument: (documentId: string) =>
    request<KnowledgeDocument>(`/api/v1/knowledge-spaces/documents/${encodeURIComponent(documentId)}`),
  publish: (documentId: string, versionId: string) =>
    request<KnowledgeDocument>(
      `/api/v1/knowledge-spaces/documents/${encodeURIComponent(documentId)}/versions/${encodeURIComponent(versionId)}/publish`,
      { method: 'POST' },
    ),
  upload: (spaceId: string, draft: FormData) =>
    request<KnowledgeDocument>(
      `/api/v1/knowledge-spaces/${encodeURIComponent(spaceId)}/documents/upload`,
      { method: 'POST', body: draft },
    ),
}
