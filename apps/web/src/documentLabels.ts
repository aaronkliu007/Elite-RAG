import type { DocumentStatus, ParseStatus } from './api/documents'

export const documentStatusLabels: Record<DocumentStatus, string> = {
  DRAFT: '草稿', IN_REVIEW: '审核中', PUBLISHED: '已发布', ARCHIVED: '已归档',
}
export const parseStatusLabels: Record<ParseStatus, string> = {
  PENDING: '等待解析', RUNNING: '解析中', SUCCEEDED: '解析成功', FAILED: '解析失败',
}
