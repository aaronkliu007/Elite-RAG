import { apiRequest } from './http'

export type QaStatus = 'RECEIVED' | 'RETRIEVING' | 'SANITIZING' | 'GENERATING' | 'VERIFYING' | 'ANSWERED' | 'REFUSED' | 'FAILED'

export interface QaCitation {
  claimId: string
  text: string
  evidenceIds: string[]
}

export interface QaQuestionResponse {
  requestId: string
  status: QaStatus
  answer: string | null
  refusal: string | null
  degraded: boolean
  citations: QaCitation[]
  completedAt: string
}

export interface QaSourceLocation {
  type: 'PAGE' | 'SLIDE' | 'TABLE' | 'MARKDOWN'
  coordinates: Record<string, unknown>
}

export interface QaSourceResponse {
  documentId: string
  displayName: string
  versionId: string
  location: QaSourceLocation
  previewUrl: string
  downloadUrl: string
}

export const qaApi = {
  ask: (question: string, idempotencyKey: string) => apiRequest<QaQuestionResponse>(
    '/api/qa/questions',
    {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ question }),
    },
  ),
  get: (requestId: string) => apiRequest<QaQuestionResponse>(
    `/api/qa/questions/${encodeURIComponent(requestId)}`,
  ),
  getSource: (requestId: string, evidenceId: string) => apiRequest<QaSourceResponse>(
    `/api/qa/questions/${encodeURIComponent(requestId)}/sources/${encodeURIComponent(evidenceId)}`,
  ),
}
