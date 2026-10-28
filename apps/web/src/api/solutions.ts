import { apiRequest } from './http'

export type SolutionPhase =
  | 'REQUIREMENTS_DRAFT' | 'REQUIREMENTS_CONFIRMED' | 'OUTLINE_DRAFT'
  | 'OUTLINE_CONFIRMED' | 'GENERATING' | 'IN_REVIEW' | 'READY_TO_FINALIZE' | 'FINALIZED'
export type SolutionAction = 'EDIT_REQUIREMENTS' | 'EDIT_OUTLINE' | 'CONFIRM_REQUIREMENTS' | 'CONFIRM_OUTLINE' | 'ADOPT_SECTION'
export type RequirementCategory =
  | 'GOAL' | 'SCOPE' | 'CONSTRAINT' | 'DELIVERABLE' | 'STAKEHOLDER' | 'DATE' | 'RISK' | 'UNKNOWN'
export type RiskType =
  | 'AMOUNT_OR_PRICE' | 'DATE_OR_DURATION' | 'PERFORMANCE_OR_SLA'
  | 'CUSTOMER_OR_CASE_FACT' | 'COMPANY_OR_PRODUCT_CAPABILITY' | 'LEGAL_OR_COMPLIANCE_COMMITMENT'

export type SolutionLocator =
  | { type: 'PAGE', pageNumber: number, box?: { left: number, top: number, right: number, bottom: number } }
  | { type: 'SLIDE', slideNumber: number }
  | { type: 'TABLE', tableName: string, startRow: number, endRow: number, startColumn: number, endColumn: number }
  | { type: 'MARKDOWN', headingPath: string[], nodeId: string | null }

export interface SolutionSource { documentId: string, versionId: string, locator: SolutionLocator }
export interface RequirementItemInput { category: RequirementCategory, text: string, sources: SolutionSource[] }
export interface RequirementItem extends RequirementItemInput { itemId: string }
export interface RequirementVersion {
  id: string, number: number, items: RequirementItem[], createdBy: string, createdAt: string, changeReason: string
}
export interface OutlineNodeInput {
  title: string, level: number, order: number, required: boolean,
  objective: string, retrievalHint: string, riskTypes: RiskType[]
}
export interface OutlineNode extends OutlineNodeInput { sectionId: string }
export interface OutlineVersion {
  id: string, number: number, nodes: OutlineNode[], createdBy: string, createdAt: string, changeReason: string
}
export interface Confirmation { versionId: string, confirmedBy: string, confirmedAt: string, valid: boolean }
export interface SolutionSummary {
  id: string, spaceId: string, name: string, phase: SolutionPhase, revision: number,
  createdAt: string, updatedAt: string
}
export interface SolutionDetail {
  id: string, spaceId: string, name: string, phase: SolutionPhase, revision: number,
  solutionRevision: number, createdAt: string, requirementVersion: RequirementVersion | null,
  outlineVersion: OutlineVersion | null, requirementConfirmation: Confirmation | null,
  outlineConfirmation: Confirmation | null, sourceAccessValid: boolean,
  adoptedSectionVersionIds: Record<string, string>, allowedActions: SolutionAction[]
}
export type SectionGenerationStatus = 'PENDING' | 'RETRIEVING' | 'GENERATING' | 'VERIFYING'
  | 'SUCCEEDED' | 'INSUFFICIENT_EVIDENCE' | 'FAILED_RETRYABLE' | 'FAILED_MANUAL' | 'CANCELLED'
export type CitationVerdict = 'SUPPORTED' | 'HUMAN_CONFIRMATION_REQUIRED' | 'PARTIAL' | 'UNSUPPORTED'
export interface SectionClaim { claimId: string, text: string, factual: boolean,
  evidenceIds: string[], riskTypes: RiskType[] }
export interface SectionVersion { id: string, content: string, claims: SectionClaim[],
  evidenceIds: string[], verification: { verifications: {
    claimId: string, verdict: CitationVerdict, reason: string }[] } }
export interface SectionGenerationAttempt { id: string, status: SectionGenerationStatus,
  executionCount: number, revision: number, failureCode: string | null,
  sectionVersion: SectionVersion | null }
export interface SolutionCommandResult {
  solutionId: string, revision: number, requirementVersionId: string,
  outlineVersionId: string | null, phase: SolutionPhase
}

const keyHeader = (key: string) => ({ 'Idempotency-Key': key })
const path = (solutionId: string) => `/api/v1/solutions/${encodeURIComponent(solutionId)}`

export const solutionsApi = {
  list: (spaceId: string, page = 1, pageSize = 20) => apiRequest<SolutionSummary[]>(
    `/api/v1/knowledge-spaces/${encodeURIComponent(spaceId)}/solutions?page=${page}&pageSize=${pageSize}`,
  ),
  get: (solutionId: string) => apiRequest<SolutionDetail>(path(solutionId)),
  create: (spaceId: string, name: string, items: RequirementItemInput[], expectedRevision: number, key: string) =>
    apiRequest<SolutionCommandResult>(`/api/v1/knowledge-spaces/${encodeURIComponent(spaceId)}/solutions`, {
      method: 'POST', headers: keyHeader(key), body: JSON.stringify({ name, items, expectedRevision }),
    }),
  reviseRequirements: (solutionId: string, items: RequirementItemInput[], changeReason: string,
    expectedRevision: number, key: string) => apiRequest<SolutionCommandResult>(`${path(solutionId)}/requirements`, {
    method: 'PUT', headers: keyHeader(key), body: JSON.stringify({ items, changeReason, expectedRevision }),
  }),
  confirmRequirements: (solutionId: string, versionId: string, expectedRevision: number, key: string) =>
    apiRequest<SolutionCommandResult>(`${path(solutionId)}/requirements/${encodeURIComponent(versionId)}/confirm`, {
      method: 'POST', headers: keyHeader(key), body: JSON.stringify({ expectedRevision }),
    }),
  reviseOutline: (solutionId: string, nodes: OutlineNodeInput[], changeReason: string,
    expectedRevision: number, key: string) =>
    apiRequest<SolutionCommandResult>(`${path(solutionId)}/outline`, {
      method: 'PUT', headers: keyHeader(key), body: JSON.stringify({ nodes, changeReason, expectedRevision }),
    }),
  confirmOutline: (solutionId: string, versionId: string, expectedRevision: number, key: string) =>
    apiRequest<SolutionCommandResult>(`${path(solutionId)}/outline/${encodeURIComponent(versionId)}/confirm`, {
      method: 'POST', headers: keyHeader(key), body: JSON.stringify({ expectedRevision }),
    }),
  generateSection: (solutionId: string, sectionId: string, expectedRevision: number,
    baselineSectionVersionId: string | null, key: string) =>
    apiRequest<SectionGenerationAttempt>(`${path(solutionId)}/sections/${encodeURIComponent(sectionId)}/generation-attempts`, {
      method: 'POST', headers: keyHeader(key), body: JSON.stringify({ expectedRevision, baselineSectionVersionId }),
    }),
  getSectionAttempt: (solutionId: string, sectionId: string, key: string) =>
    apiRequest<SectionGenerationAttempt>(`${path(solutionId)}/sections/${encodeURIComponent(sectionId)}/generation-attempts?clientKey=${encodeURIComponent(key)}`),
  adoptSection: (solutionId: string, sectionId: string, sectionVersionId: string,
    expectedRevision: number, key: string) =>
    apiRequest<SolutionCommandResult>(`${path(solutionId)}/sections/${encodeURIComponent(sectionId)}/versions/${encodeURIComponent(sectionVersionId)}/adopt`, {
      method: 'POST', headers: keyHeader(key), body: JSON.stringify({ expectedRevision }),
    }),
}
