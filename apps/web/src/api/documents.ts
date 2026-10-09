import { apiRequest } from './http'

export type ParseStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
export type DocumentStatus = 'DRAFT' | 'IN_REVIEW' | 'PUBLISHED' | 'ARCHIVED'
export type ParseErrorCode = 'UNSUPPORTED' | 'ENCRYPTED' | 'LIMIT_EXCEEDED' | 'CONVERSION_FAILED' | 'TIMEOUT'
export type DocumentAction =
  | 'VIEW' | 'PREVIEW' | 'DOWNLOAD' | 'EDIT_METADATA'
  | 'SUBMIT_REVIEW' | 'REJECT' | 'PUBLISH' | 'ARCHIVE'

export interface DocumentVersion {
  id: string
  versionNumber: number
  status: DocumentStatus
  parseStatus: ParseStatus
  createdAt: string
  parseErrorCode: ParseErrorCode | null
}

export interface DocumentReview {
  id: string
  versionId: string
  reviewerId: string
  decision: string
  comment: string
  reviewedAt: string
}

export interface KnowledgeDocument {
  id: string
  spaceId: string
  title: string
  metadata: { classification: string, tags: string[] }
  versions: DocumentVersion[]
  currentPublishedVersionId: string | null
  archived: boolean
  reviews: DocumentReview[]
  allowedActions: DocumentAction[]
}

export interface UploadAccepted {
  documentId: string
  versionId: string
  status: string
}

const keyHeader = (key: string) => ({ 'Idempotency-Key': key })

export const documentsApi = {
  list: (spaceId: string, page = 1, pageSize = 100) => apiRequest<KnowledgeDocument[]>(
    `/api/v1/knowledge-spaces/${encodeURIComponent(spaceId)}/documents?page=${page}&pageSize=${pageSize}`,
  ),
  get: (documentId: string) => apiRequest<KnowledgeDocument>(`/api/v1/documents/${encodeURIComponent(documentId)}`),
  upload: (spaceId: string, form: FormData, key: string) => apiRequest<UploadAccepted>(
    `/api/v1/knowledge-spaces/${encodeURIComponent(spaceId)}/documents`,
    { method: 'POST', headers: keyHeader(key), body: form },
  ),
  submitReview: (documentId: string, versionId: string, key: string) => apiRequest<KnowledgeDocument>(
    `/api/v1/documents/${encodeURIComponent(documentId)}/versions/${encodeURIComponent(versionId)}/submit-review`,
    { method: 'POST', headers: keyHeader(key) },
  ),
  reject: (documentId: string, versionId: string, comment: string, key: string) => apiRequest<KnowledgeDocument>(
    `/api/v1/documents/${encodeURIComponent(documentId)}/versions/${encodeURIComponent(versionId)}/reject`,
    { method: 'POST', headers: keyHeader(key), body: JSON.stringify({ comment }) },
  ),
  publish: (documentId: string, versionId: string, key: string) => apiRequest<KnowledgeDocument>(
    `/api/v1/documents/${encodeURIComponent(documentId)}/versions/${encodeURIComponent(versionId)}/publish`,
    { method: 'POST', headers: keyHeader(key) },
  ),
  previewUrl: (documentId: string) => `/api/v1/documents/${encodeURIComponent(documentId)}/preview`,
  downloadUrl: (documentId: string) => `/api/v1/documents/${encodeURIComponent(documentId)}/download`,
}
