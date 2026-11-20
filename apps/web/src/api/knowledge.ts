import { documentsApi, type DocumentStatus, type ParseStatus } from './documents'
import { ApiRequestError } from './http'

export function documentLoadError(failure: unknown, fallback: string): string {
  if (failure instanceof ApiRequestError && failure.status === 404) {
    return '无法加载文档：该空间可能已不存在，或你没有访问权限。请联系空间管理员确认。'
  }
  return failure instanceof ApiRequestError ? failure.displayMessage : failure instanceof Error ? failure.message : fallback
}

export interface KnowledgeDocumentSummary {
  id: string
  title: string
  ownerName: string
  lifecycleStatus: DocumentStatus
  parseStatus: ParseStatus
  updatedAt: string
}

export const knowledgeApi = {
  async listDocuments(spaceId: string, page = 1, pageSize = 100): Promise<KnowledgeDocumentSummary[]> {
    return (await documentsApi.list(spaceId, page, pageSize)).map((document) => {
      const latest = document.versions.at(-1)
      return {
        id: document.id,
        title: document.title,
        ownerName: '—',
        lifecycleStatus: latest?.status ?? 'DRAFT',
        parseStatus: latest?.parseStatus ?? 'PENDING',
        updatedAt: latest?.createdAt ?? new Date(0).toISOString(),
      }
    })
  },
}

export type { DocumentStatus as LifecycleStatus, ParseStatus }
