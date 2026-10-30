import { apiRequest, apiRequestBlob } from './http'

export type SolutionPhase =
  | 'REQUIREMENTS_DRAFT' | 'REQUIREMENTS_CONFIRMED' | 'OUTLINE_DRAFT'
  | 'OUTLINE_CONFIRMED' | 'GENERATING' | 'IN_REVIEW' | 'READY_TO_FINALIZE' | 'FINALIZED'
export type SolutionAction = 'EDIT_REQUIREMENTS' | 'EDIT_OUTLINE' | 'CONFIRM_REQUIREMENTS' | 'CONFIRM_OUTLINE' | 'ADOPT_SECTION' | 'CONFIRM_RISK' | 'FINALIZE'
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
export type ProposalKind = 'requirements' | 'outline'
export interface SolutionProposal {
  id: string, kind: 'REQUIREMENTS' | 'OUTLINE',
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'INDETERMINATE',
  requirementVersionId: string, expectedRevision: number, failureCode: string | null,
  result: { kind: 'REQUIREMENTS', items: RequirementItemInput[] }
    | { kind: 'OUTLINE', nodes: OutlineNodeInput[] } | null
}
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
  adoptedSectionVersionIds: Record<string, string>, finalizedVersionId: string | null,
  allowedActions: SolutionAction[]
}
export interface FinalizationRisk {
  sectionId: string, sectionTitle: string, sectionVersionId: string, claimId: string,
  claimText: string, riskTypes: RiskType[], confirmed: boolean,
  confirmedBy: string | null, confirmedAt: string | null
}
export interface FinalizationReview { solutionId: string, revision: number, risks: FinalizationRisk[] }
export interface FinalizedVersion { id: string, solutionRevision: number, contentHash: string, createdAt: string }
export type FeedbackReason = 'ACCEPTED' | 'FACT_ERROR' | 'MISSING_EVIDENCE' | 'IRRELEVANT'
  | 'OUTDATED' | 'STYLE' | 'DUPLICATE' | 'SCOPE_MISMATCH' | 'OTHER'
export interface SolutionFeedback { id: string, finalizedVersionId: string,
  sectionVersionId: string | null, reason: FeedbackReason, createdAt: string }
export type SectionGenerationStatus = 'PENDING' | 'RETRIEVING' | 'GENERATING' | 'VERIFYING'
  | 'SUCCEEDED' | 'INSUFFICIENT_EVIDENCE' | 'FAILED_RETRYABLE' | 'FAILED_MANUAL' | 'CANCELLED'
export type CitationVerdict = 'SUPPORTED' | 'HUMAN_CONFIRMATION_REQUIRED' | 'PARTIAL' | 'UNSUPPORTED'
export interface SectionClaim { claimId: string, text: string, factual: boolean,
  evidenceIds: string[], riskTypes: RiskType[] }
export interface SectionVersion { id: string, content: string, claims: SectionClaim[],
  evidenceIds: string[], verification: { verifications: {
    claimId: string, verdict: CitationVerdict, reason: string }[] } }
export interface SectionVersionSummary { id: string, kind: 'MODEL_DRAFT' | 'HUMAN_EDIT' | 'ADOPTED',
  parentVersionId: string | null, baselineVersionId: string | null,
  createdBy: string | null, createdAt: string }
export interface SectionVersionDetail extends SectionVersionSummary {
  content: string, claims: SectionClaim[],
  verification: { verifications: { claimId: string, verdict: CitationVerdict, reasonCode: string }[] }
}
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
  generateProposal: (solutionId: string, kind: ProposalKind, expectedRevision: number, key: string) =>
    apiRequest<SolutionProposal>(`${path(solutionId)}/proposals/${kind}`, {
      method: 'POST', headers: keyHeader(key), body: JSON.stringify({ expectedRevision }),
    }),
  getProposal: (solutionId: string, kind: ProposalKind, expectedRevision: number, key: string) =>
    apiRequest<SolutionProposal>(`${path(solutionId)}/proposals/${kind}?expectedRevision=${expectedRevision}`, {
      headers: keyHeader(key),
    }),
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
  sectionVersions: (solutionId: string, sectionId: string) =>
    apiRequest<SectionVersionSummary[]>(`${path(solutionId)}/sections/${encodeURIComponent(sectionId)}/versions`),
  sectionVersion: (solutionId: string, sectionId: string, versionId: string) =>
    apiRequest<SectionVersionDetail>(`${path(solutionId)}/sections/${encodeURIComponent(sectionId)}/versions/${encodeURIComponent(versionId)}`),
  latestSectionEdit: (solutionId: string, sectionId: string, baselineVersionId: string) =>
    apiRequest<{ version: SectionVersionDetail | null }>(`${path(solutionId)}/sections/${encodeURIComponent(sectionId)}/edits/latest?baselineVersionId=${encodeURIComponent(baselineVersionId)}`),
  saveSectionEdit: (solutionId: string, sectionId: string, baselineVersionId: string,
    expectedDraftVersionId: string | null, expectedRevision: number, content: string, key: string) =>
    apiRequest<SectionVersionDetail>(`${path(solutionId)}/sections/${encodeURIComponent(sectionId)}/edits`, {
      method: 'POST', headers: keyHeader(key), body: JSON.stringify({
        baselineVersionId, expectedDraftVersionId, expectedRevision, content,
      }),
    }),
  finalizationReview: (solutionId: string) => apiRequest<FinalizationReview>(`${path(solutionId)}/finalization-review`),
  confirmRisk: (solutionId: string, sectionVersionId: string, claimId: string, reason: string) =>
    apiRequest<{ id: string }>(`${path(solutionId)}/risk-confirmations`, {
      method: 'POST', body: JSON.stringify({ sectionVersionId, claimId, reason }),
    }),
  finalize: (solutionId: string, expectedRevision: number) =>
    apiRequest<FinalizedVersion>(`${path(solutionId)}/finalize`, {
      method: 'POST', body: JSON.stringify({ expectedRevision }),
    }),
  downloadWord: (solutionId: string, versionId: string) =>
    apiRequestBlob(`${path(solutionId)}/finalized-versions/${encodeURIComponent(versionId)}/word`),
  feedbackMine: (solutionId: string, versionId: string) =>
    apiRequest<SolutionFeedback[]>(`${path(solutionId)}/finalized-versions/${encodeURIComponent(versionId)}/feedback/mine`),
  submitFeedback: (solutionId: string, versionId: string, sectionVersionId: string | null,
    reason: FeedbackReason, key: string) =>
    apiRequest<SolutionFeedback>(`${path(solutionId)}/finalized-versions/${encodeURIComponent(versionId)}/feedback`, {
      method: 'POST', headers: keyHeader(key), body: JSON.stringify({ sectionVersionId, reason }),
    }),
}
