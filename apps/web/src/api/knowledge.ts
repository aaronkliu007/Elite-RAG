import { documentsApi, type DocumentStatus, type ParseStatus } from './documents'

export interface KnowledgeDocumentSummary {
  id: string
  title: string
  ownerName: string
  lifecycleStatus: DocumentStatus
  parseStatus: ParseStatus
  updatedAt: string
}

export const knowledgeApi = {
  async listDocuments(spaceId: string): Promise<KnowledgeDocumentSummary[]> {
    return (await documentsApi.list(spaceId)).map((document) => {
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
