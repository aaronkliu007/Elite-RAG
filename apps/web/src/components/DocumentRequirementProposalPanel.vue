<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ApiRequestError } from '../api/http'
import { documentsApi, type KnowledgeDocument } from '../api/documents'
import { solutionsApi, type SolutionProposal } from '../api/solutions'
import { formatSolutionLocator } from '../api/solutionLocator'

type DocumentRef = { documentId: string, versionId: string }
type SavedRequest = { key: string, query: string, documents: DocumentRef[], adoptionKey?: string }
type PendingAdoption = SavedRequest & { adoptionKey: string, expectedRevision: number }
const props = withDefaults(defineProps<{ actorId: string, solutionId: string, spaceId: string, revision: number,
  currentTexts: string[], disabled: boolean, sourceAccessValid?: boolean }>(), { sourceAccessValid: true })
const emit = defineEmits<{ adopted: [], busyChange: [value: boolean] }>()
const documents = ref<KnowledgeDocument[]>([])
const documentNames = ref<Record<string, string>>({})
const observedVersions = ref<Record<string, string | null>>({})
const page = ref(1)
const more = ref(false)
const selected = ref<DocumentRef[]>([])
const query = ref('')
const saved = ref<SavedRequest | null>(null)
const pending = ref<PendingAdoption | null>(null)
const resetAllowed = ref(false)
const pendingResetAllowed = ref(false)
const attempt = ref<SolutionProposal | null>(null)
const busy = ref(false)
const loadingDocuments = ref(false)
const reviewed = ref(false)
const excerpts = ref<Record<string, string>>({})
const error = ref('')
const documentError = ref('')
let disposed = false
const storageKey = `document-requirement-proposal:${props.actorId}:${props.solutionId}:${props.revision}`
const pendingStorageKey = `document-requirement-adoption:${props.actorId}:${props.solutionId}`
const published = computed(() => documents.value.filter(document => !document.archived && document.currentPublishedVersionId
  && document.allowedActions.includes('VIEW') && document.versions.some(version =>
    version.id === document.currentPublishedVersionId && version.status === 'PUBLISHED' && version.parseStatus === 'SUCCEEDED')))
const candidate = computed(() => attempt.value?.result?.kind === 'REQUIREMENTS' ? attempt.value.result : null)
const staleSelection = computed(() => selected.value.some(item => item.documentId in observedVersions.value
  && observedVersions.value[item.documentId] !== item.versionId))
const categoryLabels: Record<string, string> = { GOAL: '目标', SCOPE: '范围', CONSTRAINT: '约束',
  DELIVERABLE: '交付物', STAKEHOLDER: '相关方', DATE: '时间', RISK: '风险', UNKNOWN: '待确认' }
const statuses = { RUNNING: '正在处理，请查询原请求', SUCCEEDED: '候选待人工审阅', FAILED: '请求已失败，可放弃后重新生成',
  INDETERMINATE: '结果不确定，请联系管理员核查后再发起新请求' }
const canAdopt = computed(() => !props.disabled && props.sourceAccessValid && !busy.value && !pending.value && reviewed.value
  && attempt.value?.status === 'SUCCEEDED' && !!candidate.value)
function setBusy(value: boolean) { busy.value = value; emit('busyChange', value) }
function showError(failure: unknown) { error.value = failure instanceof ApiRequestError ? failure.displayMessage : '操作失败，请稍后查询原请求' }
function persist() { if (saved.value) sessionStorage.setItem(storageKey, JSON.stringify(saved.value)) }
function documentName(document: DocumentRef) {
  return documentNames.value[`${document.documentId}:${document.versionId}`] ?? `文档 ${document.documentId}`
}
async function inspectEvidence(evidenceId: string) {
  if (!saved.value || busy.value || pending.value) return
  const request = saved.value
  setBusy(true); error.value = ''
  try {
    const result = await solutionsApi.getDocumentProposalEvidence(props.solutionId, evidenceId, request.query,
      request.documents, props.revision, request.key)
    if (!disposed) excerpts.value = { ...excerpts.value, [evidenceId]: result.text }
  } catch (failure) {
    if (!disposed) {
      showError(failure)
      if (failure instanceof ApiRequestError && [404, 409, 422].includes(failure.status)) {
        excerpts.value = {}; attempt.value = null; reviewed.value = false; resetAllowed.value = true
      }
    }
  } finally { setBusy(false) }
}
async function loadDocuments(nextPage: number) {
  if (nextPage < 1 || loadingDocuments.value) return
  loadingDocuments.value = true
  documentError.value = ''
  try {
    const values = await documentsApi.list(props.spaceId, nextPage, 50)
    if (!disposed) {
      documents.value = values; page.value = nextPage; more.value = values.length === 50
      const names = { ...documentNames.value }
      const versions = { ...observedVersions.value }
      for (const document of values) {
        versions[document.id] = document.archived ? null : document.currentPublishedVersionId
        if (document.currentPublishedVersionId && !document.archived && document.allowedActions.includes('VIEW'))
          names[`${document.id}:${document.currentPublishedVersionId}`] = document.title
      }
      documentNames.value = names
      observedVersions.value = versions
    }
  } catch (failure) { if (!disposed) documentError.value = failure instanceof ApiRequestError ? failure.displayMessage : '加载文档失败' }
  finally { loadingDocuments.value = false }
}
function toggle(document: KnowledgeDocument, checked: boolean) {
  const versionId = document.currentPublishedVersionId
  if (!versionId || saved.value || pending.value || props.disabled || !props.sourceAccessValid) return
  if (!checked) selected.value = selected.value.filter(item => item.documentId !== document.id)
  else if (selected.value.length < 10 && !selected.value.some(item => item.documentId === document.id))
    selected.value.push({ documentId: document.id, versionId })
}
function versionChanged(document: KnowledgeDocument) {
  return selected.value.some(item => item.documentId === document.id && item.versionId !== document.currentPublishedVersionId)
}
function removeSelected(document: DocumentRef) {
  if (saved.value || pending.value || props.disabled || !props.sourceAccessValid || busy.value) return
  selected.value = selected.value.filter(item => item.documentId !== document.documentId || item.versionId !== document.versionId)
}
function clearAttempt() {
  if (props.disabled || busy.value || !attempt.value || saved.value?.adoptionKey
      || !['FAILED', 'SUCCEEDED'].includes(attempt.value.status)) return
  sessionStorage.removeItem(storageKey)
  saved.value = null; attempt.value = null; excerpts.value = {}; reviewed.value = false; error.value = ''
}
function clearUnavailable() {
  if (!resetAllowed.value || pending.value || busy.value || props.disabled) return
  sessionStorage.removeItem(storageKey)
  saved.value = null; attempt.value = null; selected.value = []; excerpts.value = {}; reviewed.value = false; resetAllowed.value = false; error.value = ''
}
function clearPendingAdoption() {
  if (!pendingResetAllowed.value || !pending.value || busy.value) return
  sessionStorage.removeItem(`document-requirement-proposal:${props.actorId}:${props.solutionId}:${pending.value.expectedRevision}`)
  sessionStorage.removeItem(pendingStorageKey)
  pending.value = null; saved.value = null; attempt.value = null; selected.value = []; excerpts.value = {}; reviewed.value = false; pendingResetAllowed.value = false; error.value = ''
}
async function generate() {
  if (props.disabled || !props.sourceAccessValid || busy.value || attempt.value || pending.value || (!saved.value && staleSelection.value)) return
  if (!saved.value) {
    if (!query.value.trim() || !selected.value.length) return
    saved.value = { key: crypto.randomUUID(), query: query.value.trim(), documents: selected.value.map(item => ({ ...item })) }
    persist()
  }
  const current = saved.value
  setBusy(true); error.value = ''
  try {
    const result = await solutionsApi.generateDocumentProposal(props.solutionId, current.query, current.documents, props.revision, current.key)
    if (!disposed) attempt.value = result
  } catch (failure) {
    if (!disposed) {
      showError(failure)
      if (failure instanceof ApiRequestError && failure.code === 'MODEL_PROPOSAL_UNAVAILABLE') {
        sessionStorage.removeItem(storageKey); saved.value = null
      }
      if (failure instanceof ApiRequestError && [404, 409, 422].includes(failure.status)) resetAllowed.value = true
    }
  } finally { setBusy(false) }
}
async function lookup() {
  if (!saved.value || busy.value) return
  const current = saved.value
  setBusy(true); error.value = ''
  try {
    const result = await solutionsApi.getDocumentProposal(props.solutionId, current.query, current.documents, props.revision, current.key)
    if (!disposed) { attempt.value = result; resetAllowed.value = false }
  } catch (failure) {
    if (!disposed) {
      showError(failure)
      if (failure instanceof ApiRequestError && [404, 409, 422].includes(failure.status)) {
        attempt.value = null; excerpts.value = {}; reviewed.value = false; resetAllowed.value = true
      }
    }
  } finally { setBusy(false) }
}
async function adopt() {
  if (!canAdopt.value || !candidate.value || !saved.value || pending.value) return
  saved.value.adoptionKey ||= crypto.randomUUID(); persist()
  pending.value = { ...saved.value, adoptionKey: saved.value.adoptionKey, expectedRevision: props.revision }
  sessionStorage.setItem(pendingStorageKey, JSON.stringify(pending.value))
  await recoverAdoption()
}
async function recoverAdoption() {
  if (!pending.value || busy.value || props.disabled) return
  const current = pending.value
  setBusy(true); error.value = ''
  try {
    await solutionsApi.adoptDocumentProposal(props.solutionId, current.query, current.documents,
      current.expectedRevision, current.key, current.adoptionKey)
    if (!disposed) {
      sessionStorage.removeItem(`document-requirement-proposal:${props.actorId}:${props.solutionId}:${current.expectedRevision}`)
      sessionStorage.removeItem(pendingStorageKey); pending.value = null; emit('adopted')
    }
  } catch (failure) {
    if (!disposed) {
      showError(failure)
      pendingResetAllowed.value = failure instanceof ApiRequestError && [404, 409, 422].includes(failure.status)
    }
  }
  finally { setBusy(false) }
}
onMounted(() => {
  const pendingRaw = sessionStorage.getItem(pendingStorageKey)
  if (pendingRaw) {
    try {
      const data: unknown = JSON.parse(pendingRaw)
      if (data && typeof data === 'object' && 'key' in data && 'query' in data && 'documents' in data
          && 'adoptionKey' in data && 'expectedRevision' in data && typeof data.key === 'string'
          && typeof data.query === 'string' && typeof data.adoptionKey === 'string'
          && typeof data.expectedRevision === 'number' && Number.isSafeInteger(data.expectedRevision)
          && data.expectedRevision >= 0 && Array.isArray(data.documents) && data.documents.length > 0
          && data.documents.length <= 10 && data.documents.every(item => item
            && typeof item.documentId === 'string' && typeof item.versionId === 'string')) {
        pending.value = data as PendingAdoption
      } else sessionStorage.removeItem(pendingStorageKey)
    } catch { sessionStorage.removeItem(pendingStorageKey) }
  }
  const raw = sessionStorage.getItem(storageKey)
  if (raw) {
    try {
      const data: unknown = JSON.parse(raw)
      if (data && typeof data === 'object' && 'key' in data && 'query' in data && 'documents' in data
          && typeof data.key === 'string' && typeof data.query === 'string' && Array.isArray(data.documents)
          && data.documents.length > 0 && data.documents.length <= 10
          && data.documents.every(item => item && typeof item.documentId === 'string' && typeof item.versionId === 'string')) {
        saved.value = data as SavedRequest; query.value = saved.value.query; selected.value = saved.value.documents.map(item => ({ ...item }))
        if (!pending.value) void lookup()
      } else sessionStorage.removeItem(storageKey)
    } catch { sessionStorage.removeItem(storageKey) }
  }
  void loadDocuments(1)
})
onUnmounted(() => { disposed = true })
</script>

<template>
  <section
    class="document-proposal"
    aria-label="从文档提取需求"
    :aria-busy="busy || loadingDocuments"
  >
    <h4>从文档提取需求</h4>
    <p>选择最多 10 个已发布文档，输入关注问题。系统仅使用检索到的部分片段，可能无法覆盖完整文档；请逐条核对来源。</p>
    <div
      v-if="pending"
      class="notice"
      role="status"
    >
      <p>上次采纳请求的结果尚未确认，请先查询原采纳结果；查询会沿用原请求键。</p>
      <button
        class="button button-secondary"
        type="button"
        data-action="recover-document-adoption"
        :disabled="busy || disabled"
        @click="recoverAdoption"
      >
        查询原采纳结果
      </button>
      <p v-if="pendingResetAllowed">
        原采纳请求可能已经保存；放弃追踪不会撤销已有修订。
      </p>
      <button
        v-if="pendingResetAllowed"
        class="button button-secondary"
        type="button"
        :disabled="busy"
        @click="clearPendingAdoption"
      >
        放弃追踪并更换文档
      </button>
    </div>
    <p
      v-if="disabled && !busy"
      class="notice"
    >
      请先处理当前未保存的编辑内容。
    </p>
    <label class="query-label">关注问题
      <textarea
        v-model="query"
        data-action="document-query"
        rows="2"
        maxlength="2000"
        :disabled="disabled || !sourceAccessValid || busy || !!saved || !!pending"
        placeholder="例如：项目建设目标、范围和约束"
      />
    </label>
    <p>已选择 {{ selected.length }} / 10 个文档</p>
    <ul
      v-if="selected.length"
      aria-label="已选择的文档"
      class="selected-documents"
    >
      <li
        v-for="item in selected"
        :key="`${item.documentId}:${item.versionId}`"
      >
        <span>{{ documentName(item) }} · 版本 {{ item.versionId }}</span>
        <button
          class="button button-secondary"
          type="button"
          :data-remove-document="item.documentId"
          :aria-label="`移除 ${documentName(item)}`"
          :disabled="disabled || !sourceAccessValid || busy || !!saved || !!pending"
          @click="removeSelected(item)"
        >
          移除
        </button>
      </li>
    </ul>
    <p
      v-if="staleSelection && !saved && !pending"
      class="notice"
      role="status"
    >
      文档已换版，请移除旧版本后重新选择。
    </p>
    <p
      v-if="documentError"
      role="alert"
      class="notice"
    >
      {{ documentError }}
    </p>
    <p v-if="loadingDocuments">
      正在加载文档…
    </p>
    <p v-else-if="!published.length">
      本页没有可读取且解析完成的已发布文档。
    </p>
    <div class="document-list">
      <label
        v-for="document in published"
        :key="document.id"
        class="document-row"
      >
        <input
          type="checkbox"
          :data-document="document.id"
          :checked="selected.some(item => item.documentId === document.id && item.versionId === document.currentPublishedVersionId)"
          :disabled="disabled || !sourceAccessValid || busy || !!saved || !!pending || versionChanged(document) || (selected.length >= 10 && !selected.some(item => item.documentId === document.id))"
          @change="toggle(document, ($event.target as HTMLInputElement).checked)"
        >
        <span>{{ document.title }} <small>· 已发布版本 {{ document.currentPublishedVersionId }}</small></span>
      </label>
    </div>
    <div class="actions pagination">
      <button
        class="button button-secondary"
        type="button"
        :disabled="loadingDocuments || page <= 1"
        @click="loadDocuments(page - 1)"
      >
        上一页
      </button>
      <span>第 {{ page }} 页</span>
      <button
        class="button button-secondary"
        type="button"
        :disabled="loadingDocuments || !more"
        @click="loadDocuments(page + 1)"
      >
        下一页
      </button>
    </div>
    <div class="actions">
      <button
        class="button button-primary"
        type="button"
        data-action="generate-document-proposal"
        :disabled="disabled || !sourceAccessValid || busy || !!attempt || !!pending || (!saved && (!selected.length || !query.trim() || staleSelection))"
        @click="generate"
      >
        {{ busy ? '处理中…' : saved ? '重发原请求（沿用原键）' : '提取需求候选' }}
      </button>
      <button
        v-if="saved && !pending"
        class="button button-secondary"
        type="button"
        :disabled="busy"
        @click="lookup"
      >
        查询原请求
      </button>
      <button
        v-if="attempt && ['FAILED', 'SUCCEEDED'].includes(attempt.status) && !saved?.adoptionKey && !pending"
        class="button button-secondary"
        type="button"
        data-action="discard-document-proposal"
        :disabled="disabled || busy"
        @click="clearAttempt"
      >
        放弃本次候选
      </button>
    </div>
    <div
      v-if="resetAllowed && saved && !pending"
      class="notice"
    >
      原请求无法访问或输入已失效。更换文档会创建新请求，原请求可能已经调用过模型。
      <button
        class="button button-secondary"
        type="button"
        :disabled="disabled || busy"
        @click="clearUnavailable"
      >
        放弃原请求并更换文档
      </button>
    </div>
    <p
      v-if="error"
      role="alert"
      class="notice"
    >
      {{ error }}
    </p>
    <p
      v-if="attempt"
      role="status"
    >
      {{ statuses[attempt.status] }}
    </p>
    <template v-if="candidate && attempt?.status === 'SUCCEEDED'">
      <p
        v-if="candidate.retrievalDegraded"
        class="notice"
      >
        检索服务发生降级，请仔细核对文档覆盖范围。
      </p>
      <div
        v-if="candidate.documentsWithoutEvidence?.length"
        class="notice"
      >
        以下已选文档未检索到片段：
        <ul>
          <li
            v-for="item in candidate.documentsWithoutEvidence"
            :key="item.documentId"
            :data-missing-document="item.documentId"
          >
            {{ documentName(item) }} · 版本 {{ item.versionId }}
          </li>
        </ul>
      </div>
      <div class="comparison">
        <div>
          <h5>当前需求</h5><ol>
            <li
              v-for="(text, index) in currentTexts"
              :key="index"
            >
              {{ text }}
            </li>
          </ol>
        </div>
        <div>
          <h5>提取候选</h5><ol>
            <li
              v-for="(item, index) in candidate.items"
              :key="index"
            >
              <p>{{ item.text }}</p><small>分类：{{ categoryLabels[item.category] }}</small>
              <ul>
                <li
                  v-for="source in item.sources"
                  :key="`${source.documentId}:${source.versionId}:${formatSolutionLocator(source.locator)}`"
                  :data-source-document="source.documentId"
                >
                  {{ documentName(source) }} · 版本 {{ source.versionId }} · {{ formatSolutionLocator(source.locator) }}
                  <div
                    v-if="source.evidenceIds?.length"
                    class="evidence-list"
                  >
                    <small>供核对的是按规则处理的脱敏片段。</small>
                    <div
                      v-for="(evidenceId, evidenceIndex) in source.evidenceIds"
                      :key="evidenceId"
                    >
                      <button
                        class="button button-secondary"
                        type="button"
                        :data-evidence="evidenceId"
                        :disabled="busy || !!pending"
                        @click="inspectEvidence(evidenceId)"
                      >
                        核对原文片段 {{ evidenceIndex + 1 }}
                      </button>
                      <p
                        v-if="excerpts[evidenceId]"
                        class="excerpt"
                      >
                        {{ excerpts[evidenceId] }}
                      </p>
                    </div>
                  </div>
                  <p
                    v-else
                    class="notice"
                  >
                    无法定位原文片段，请重新生成候选。
                  </p>
                </li>
              </ul>
            </li>
          </ol>
        </div>
      </div>
      <div class="adoption">
        <label><input
          v-model="reviewed"
          type="checkbox"
          data-action="review-document-proposal"
          :disabled="busy || disabled"
        > 我已核对候选及来源，知晓仍需异人确认</label>
        <button
          class="button button-primary"
          type="button"
          data-action="adopt-document-proposal"
          :disabled="!canAdopt"
          @click="adopt"
        >
          采纳为待确认修订
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.document-proposal { margin: 18px 0; padding: 18px; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: var(--color-surface, #fff); min-width: 0; }
h4, h5 { margin: 0 0 10px; } p, li, label, small { overflow-wrap: anywhere; line-height: 1.6; } textarea { display: block; box-sizing: border-box; width: 100%; padding: 10px; font: inherit; border: 1px solid var(--color-border); border-radius: var(--radius-control); }
.document-row { display: flex; align-items: flex-start; gap: 8px; margin: 8px 0; min-width: 0; }.document-row span { min-width: 0; }.document-row small { color: var(--color-text-muted); }
.selected-documents li { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }.selected-documents li span { flex: 1 1 240px; min-width: 0; }
.actions, .adoption { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin: 14px 0; }.adoption label { display: flex; align-items: flex-start; gap: 8px; }
.comparison { display: grid; grid-template-columns: minmax(0,1fr) minmax(0,1fr); gap: 20px; }.comparison > div { min-width: 0; padding: 14px; background: var(--color-background, #f5f7fa); border-radius: var(--radius-control); }
ol, ul { padding-left: 22px; } li { margin: 8px 0; white-space: pre-wrap; }.notice { color: #8a3d0a; }
.button { min-height: 44px; padding: 0 16px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); appearance: none; font: inherit; font-weight: 750; cursor: pointer; }.button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; }.button-primary:hover:not(:disabled) { background: var(--color-primary-strong); border-color: var(--color-primary-strong); }.button-secondary { color: var(--color-primary); background: #fff; }.button:disabled { opacity: .52; cursor: not-allowed; } button:focus-visible, input:focus-visible, textarea:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
@media (max-width: 760px) { .comparison { grid-template-columns: minmax(0,1fr); }.actions:not(.pagination) .button, .adoption .button { width: 100%; } }
</style>
