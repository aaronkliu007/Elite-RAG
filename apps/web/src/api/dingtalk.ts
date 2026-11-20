import { apiRequest } from './http'
export interface DingTalkStatus { configured: boolean, authoritativeScopeConfirmed: boolean }
export interface DingTalkSummary { departmentCount: number, userCount: number, capturedAt: string }
export interface DingTalkDepartment { id: string, parentId: string | null, name: string }
export const dingtalkApi = {
  status: () => apiRequest<DingTalkStatus>('/api/v1/admin/dingtalk'),
  departments: () => apiRequest<DingTalkDepartment[]>('/api/v1/admin/dingtalk/departments'),
  preview: () => apiRequest<DingTalkSummary>('/api/v1/admin/dingtalk/preview', { method: 'POST' }),
  sync: () => apiRequest<DingTalkSummary>('/api/v1/admin/dingtalk/sync', { method: 'POST' }),
}
