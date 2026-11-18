import { apiRequest } from './http'

export type SourceLocator =
  | { type: 'PAGE', pageNumber: number, boundingBox: null | { left: number, top: number, right: number, bottom: number } }
  | { type: 'SLIDE', slideNumber: number }
  | { type: 'TABLE', tableName: string, startRow: number, endRow: number, startColumn: number, endColumn: number }
  | { type: 'MARKDOWN' | 'SECTION', headingPath: string[], nodeId: string }

export interface SearchEvidence {
  evidenceId: string
  documentId: string
  versionId: string
  nodeId: string
  title: string
  excerpt: string
  score: number
  locator: SourceLocator
}

export interface SearchResponse {
  requestId: string
  evidence: SearchEvidence[]
  degraded: boolean
  degradationReasons: string[]
  stats: { lexicalCount: number, vectorCount: number, fusedCount: number, reauthorizedCount: number, selectedCount: number }
  completedAt: string
}

export const searchApi = {
  search: (spaceId: string, queryText: string, requestedEvidenceCount = 10, documentId?: string) =>
    apiRequest<SearchResponse>('/api/v1/search', {
      method: 'POST',
      body: JSON.stringify({ spaceId, queryText, metadataFilter: documentId ? { documentId } : {}, requestedEvidenceCount }),
    }),
}
