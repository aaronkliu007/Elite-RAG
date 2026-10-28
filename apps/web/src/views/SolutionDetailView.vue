<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, onBeforeRouteLeave, useRoute } from 'vue-router'

import { ApiRequestError } from '../api/http'
import { searchApi, type SearchEvidence, type SourceLocator } from '../api/search'
import { solutionsApi, type OutlineNodeInput, type RequirementCategory, type RequirementItemInput,
  type RiskType, type SolutionDetail, type SolutionLocator, type SolutionSource } from '../api/solutions'

interface RequirementDraft extends RequirementItemInput {
  searchQuery: string
  evidence: SearchEvidence[]
  searching: boolean
}

const route = useRoute()
const solutionId = computed(() => String(route.params.solutionId ?? ''))
const solution = ref<SolutionDetail | null>(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const mutationError = ref('')
const conflict = ref(false)
const requirementDrafts = ref<RequirementDraft[]>([])
const outlineDrafts = ref<OutlineNodeInput[]>([])
const changeReason = ref('')
const outlineChangeReason = ref('')
const dirtyRequirements = ref(false)
const dirtyOutline = ref(false)
const pendingKey = ref('')

const canEditRequirements = computed(() => solution.value?.allowedActions.includes('EDIT_REQUIREMENTS') ?? false)
const canEditOutline = computed(() => solution.value?.allowedActions.includes('EDIT_OUTLINE') ?? false)
const canConfirmRequirements = computed(() => solution.value?.allowedActions.includes('CONFIRM_REQUIREMENTS') ?? false)
const canConfirmOutline = computed(() => solution.value?.allowedActions.includes('CONFIRM_OUTLINE') ?? false)
const dirty = computed(() => dirtyRequirements.value || dirtyOutline.value)
const phaseLabel: Record<SolutionDetail['phase'], string> = {
  REQUIREMENTS_DRAFT: '需求草稿', REQUIREMENTS_CONFIRMED: '需求已确认',
  OUTLINE_DRAFT: '大纲草稿', OUTLINE_CONFIRMED: '大纲已确认',
  GENERATING: '生成中', IN_REVIEW: '评审中', READY_TO_FINALIZE: '待定稿', FINALIZED: '已定稿',
}

const categories: { value: RequirementCategory, label: string }[] = [
  { value: 'GOAL', label: '目标' }, { value: 'SCOPE', label: '范围' },
  { value: 'CONSTRAINT', label: '约束' }, { value: 'DELIVERABLE', label: '交付物' },
  { value: 'STAKEHOLDER', label: '相关方' }, { value: 'DATE', label: '时间' },
  { value: 'RISK', label: '风险' }, { value: 'UNKNOWN', label: '待确认' },
]
const risks: { value: RiskType, label: string }[] = [
  { value: 'AMOUNT_OR_PRICE', label: '金额或价格' },
  { value: 'DATE_OR_DURATION', label: '日期或周期' },
  { value: 'PERFORMANCE_OR_SLA', label: '性能或服务级别' },
  { value: 'CUSTOMER_OR_CASE_FACT', label: '客户或案例事实' },
  { value: 'COMPANY_OR_PRODUCT_CAPABILITY', label: '公司或产品能力' },
  { value: 'LEGAL_OR_COMPLIANCE_COMMITMENT', label: '法律或合规承诺' },
]

function message(failure: unknown, fallback: string) {
  return failure instanceof ApiRequestError ? failure.displayMessage : fallback
}

function blankRequirement(): RequirementDraft {
  return { category: 'UNKNOWN', text: '', sources: [], searchQuery: '', evidence: [], searching: false }
}

function blankOutline(order: number): OutlineNodeInput {
  return { title: '', level: 1, order, required: true, objective: '', retrievalHint: '', riskTypes: [] }
}

function adoptLoaded(value: SolutionDetail) {
  solution.value = value
  requirementDrafts.value = value.requirementVersion?.items.map((item) => ({
    category: item.category, text: item.text, sources: item.sources.map((source) => ({ ...source })),
    searchQuery: '', evidence: [], searching: false,
  })) ?? [blankRequirement()]
  outlineDrafts.value = value.outlineVersion?.nodes.map((node) => ({
    title: node.title, level: node.level, order: node.order, required: node.required,
    objective: node.objective, retrievalHint: node.retrievalHint, riskTypes: [...node.riskTypes],
  })) ?? [blankOutline(1)]
  dirtyRequirements.value = false
  dirtyOutline.value = false
  changeReason.value = ''
  outlineChangeReason.value = ''
  pendingKey.value = ''
  conflict.value = false
}

async function load(force = false) {
  if (dirty.value && !force && !window.confirm('存在未保存的修改，确定重新加载吗？')) return
  loading.value = true
  error.value = ''
  mutationError.value = ''
  try {
    adoptLoaded(await solutionsApi.get(solutionId.value))
  } catch (failure) {
    if (!dirty.value) solution.value = null
    error.value = message(failure, '加载方案失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function locatorFromSearch(locator: SourceLocator): SolutionLocator {
  switch (locator.type) {
    case 'PAGE': return locator.boundingBox
      ? { type: 'PAGE', pageNumber: locator.pageNumber, box: locator.boundingBox }
      : { type: 'PAGE', pageNumber: locator.pageNumber }
    case 'SLIDE': return { type: 'SLIDE', slideNumber: locator.slideNumber }
    case 'TABLE': return { ...locator }
    case 'MARKDOWN': return { ...locator }
  }
}

async function searchSource(index: number) {
  const item = requirementDrafts.value[index]
  if (!item || !solution.value || !item.searchQuery.trim()) return
  item.searching = true
  mutationError.value = ''
  try {
    item.evidence = (await searchApi.search(solution.value.spaceId, item.searchQuery.trim())).evidence
  } catch (failure) {
    mutationError.value = message(failure, '检索来源失败，请稍后重试')
  } finally {
    item.searching = false
  }
}

function addSource(index: number, evidence: SearchEvidence) {
  const item = requirementDrafts.value[index]
  if (!item) return
  const source: SolutionSource = {
    documentId: evidence.documentId, versionId: evidence.versionId,
    locator: locatorFromSearch(evidence.locator),
  }
  if (!item.sources.some((candidate) => JSON.stringify(candidate) === JSON.stringify(source))) {
    item.sources.push(source)
    dirtyRequirements.value = true
  }
}

function removeRequirement(index: number) {
  if (requirementDrafts.value.length < 2) return
  requirementDrafts.value.splice(index, 1)
  dirtyRequirements.value = true
}

function moveOutline(index: number, direction: -1 | 1) {
  const target = index + direction
  if (target < 0 || target >= outlineDrafts.value.length) return
  const current = outlineDrafts.value[index]!
  const adjacent = outlineDrafts.value[target]!
  outlineDrafts.value[index] = adjacent
  outlineDrafts.value[target] = current
  outlineDrafts.value.forEach((node, position) => { node.order = position + 1 })
  dirtyOutline.value = true
}

function removeOutline(index: number) {
  if (outlineDrafts.value.length < 2) return
  outlineDrafts.value.splice(index, 1)
  outlineDrafts.value.forEach((node, position) => { node.order = position + 1 })
  dirtyOutline.value = true
}

async function mutate(operation: (key: string) => Promise<unknown>) {
  if (saving.value) return
  pendingKey.value ||= crypto.randomUUID()
  saving.value = true
  mutationError.value = ''
  conflict.value = false
  try {
    await operation(pendingKey.value)
    pendingKey.value = ''
    await load(true)
  } catch (failure) {
    mutationError.value = message(failure, '保存失败，当前输入已保留')
    if (failure instanceof ApiRequestError && failure.status === 409) {
      conflict.value = true
      pendingKey.value = ''
    }
  } finally {
    saving.value = false
  }
}

async function saveRequirements() {
  if (!solution.value || !canEditRequirements.value || !changeReason.value.trim()) return
  if (dirtyOutline.value) { mutationError.value = '请先处理未保存的大纲修改'; return }
  const items = requirementDrafts.value.map((item) => ({
    category: item.category, text: item.text.trim(), sources: item.sources,
  }))
  if (items.some((item) => !item.text || (item.category !== 'UNKNOWN' && item.sources.length === 0))) {
    mutationError.value = '需求正文不能为空，已知事实必须选择来源'
    return
  }
  await mutate((key) => solutionsApi.reviseRequirements(solutionId.value, items,
    changeReason.value.trim(), solution.value!.revision, key))
}

async function confirmRequirements() {
  if (!solution.value?.requirementVersion || !canConfirmRequirements.value || dirty.value) return
  const version = solution.value.requirementVersion
  await mutate((key) => solutionsApi.confirmRequirements(solutionId.value,
    version.id, solution.value!.revision, key))
}

async function saveOutline() {
  if (!solution.value || !canEditOutline.value || !outlineChangeReason.value.trim()) return
  if (dirtyRequirements.value) { mutationError.value = '请先处理未保存的需求修改'; return }
  const nodes = outlineDrafts.value.map((node, index) => ({ ...node, order: index + 1 }))
  if (nodes.some((node) => !node.title.trim() || !node.objective.trim() || !node.retrievalHint.trim())
      || !nodes.some((node) => node.required)) {
    mutationError.value = '请填写章节标题、目标和检索提示，并保留至少一个必填章节'
    return
  }
  await mutate((key) => solutionsApi.reviseOutline(solutionId.value, nodes,
    outlineChangeReason.value.trim(), solution.value!.revision, key))
}

async function confirmOutline() {
  if (!solution.value?.outlineVersion || !canConfirmOutline.value || dirty.value) return
  const version = solution.value.outlineVersion
  await mutate((key) => solutionsApi.confirmOutline(solutionId.value,
    version.id, solution.value!.revision, key))
}

onBeforeRouteLeave(() => !dirty.value || window.confirm('存在未保存的修改，确定离开方案吗？'))
function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
onUnmounted(() => window.removeEventListener('beforeunload', beforeUnload))
</script>

<template>
  <section class="solution-detail">
    <RouterLink
      class="back-link"
      :to="solution ? `/spaces/${solution.spaceId}/solutions` : '/solutions'"
    >
      ← 返回方案列表
    </RouterLink>
    <div
      v-if="loading"
      class="state"
      aria-live="polite"
    >
      正在加载方案…
    </div>
    <div
      v-else-if="error && !solution"
      class="state state-error"
      role="alert"
    >
      {{ error }} <button
        type="button"
        @click="load()"
      >
        重试
      </button>
    </div>
    <template v-else-if="solution">
      <header class="detail-header">
        <div class="header-copy">
          <p class="eyebrow">
            方案工作台 · 第 {{ solution.solutionRevision }} 轮
          </p>
          <h2 :title="solution.name">
            {{ solution.name }}
          </h2>
          <p :title="solution.id">
            方案 ID：{{ solution.id }}
          </p>
        </div>
        <div class="header-meta">
          <span class="phase-tag">{{ phaseLabel[solution.phase] }}</span><span>聚合修订 {{ solution.revision }}</span>
        </div>
      </header>

      <div
        v-if="!solution.sourceAccessValid"
        class="state state-warning"
        role="status"
      >
        来源权限或发布状态已变化，历史正文已隐藏，原确认不再有效。可重新录入有权来源。
      </div>
      <div
        v-if="mutationError"
        class="state state-error"
        role="alert"
      >
        {{ mutationError }}
        <button
          v-if="conflict"
          type="button"
          data-action="reload-conflict"
          @click="load()"
        >
          重新加载
        </button>
      </div>

      <section
        class="step-card"
        :inert="saving ? true : undefined"
        aria-labelledby="requirements-heading"
      >
        <div class="step-heading">
          <div>
            <p class="eyebrow">
              步骤 1
            </p><h3 id="requirements-heading">
              需求与来源
            </h3>
          </div>
          <span
            v-if="solution.requirementVersion"
            class="version-tag"
            :title="solution.requirementVersion.id"
          >V{{ solution.requirementVersion.number }}</span>
        </div>
        <p class="step-note">
          已知事实必须绑定当前有权且已发布的文档版本。待明确事项请选择“待确认”。
        </p>
        <div
          v-if="solution.requirementConfirmation"
          class="confirmation"
          :class="{ invalid: !solution.requirementConfirmation.valid }"
        >
          {{ solution.requirementConfirmation.valid ? '已确认' : '确认已失效' }} · 版本 {{ solution.requirementConfirmation.versionId }} ·
          {{ solution.requirementConfirmation.confirmedBy }} · {{ new Date(solution.requirementConfirmation.confirmedAt).toLocaleString('zh-CN') }}
        </div>

        <div
          v-for="(item, index) in requirementDrafts"
          :key="index"
          class="item-card"
        >
          <div class="item-heading">
            <strong>需求 {{ index + 1 }}</strong>
            <button
              v-if="canEditRequirements && requirementDrafts.length > 1"
              type="button"
              @click="removeRequirement(index)"
            >
              移除
            </button>
          </div>
          <div class="field-grid">
            <label>分类<select
              v-model="item.category"
              :disabled="!canEditRequirements"
              @change="dirtyRequirements = true"
            >
              <option
                v-for="category in categories"
                :key="category.value"
                :value="category.value"
              >{{ category.label }}</option>
            </select></label>
            <label class="wide-field">需求正文<textarea
              v-model="item.text"
              :name="`requirement-text-${index}`"
              rows="3"
              :readonly="!canEditRequirements"
              @input="dirtyRequirements = true"
            /></label>
          </div>
          <div class="source-area">
            <strong>来源 {{ item.sources.length }} 条</strong>
            <ul v-if="item.sources.length">
              <li
                v-for="(source, sourceIndex) in item.sources"
                :key="`${source.documentId}-${source.versionId}-${sourceIndex}`"
              >
                <span :title="source.documentId">文档 {{ source.documentId }}</span><small :title="source.versionId">版本 {{ source.versionId }}</small>
                <button
                  v-if="canEditRequirements"
                  type="button"
                  @click="item.sources.splice(sourceIndex, 1); dirtyRequirements = true"
                >
                  移除来源
                </button>
              </li>
            </ul>
            <div
              v-if="canEditRequirements && item.category !== 'UNKNOWN'"
              class="source-picker"
            >
              <label>检索可用来源<input
                v-model="item.searchQuery"
                :name="`source-query-${index}`"
                placeholder="输入主题或关键词"
              ></label>
              <button
                type="button"
                :disabled="item.searching || !item.searchQuery.trim()"
                @click="searchSource(index)"
              >
                {{ item.searching ? '检索中…' : '检索来源' }}
              </button>
            </div>
            <ul
              v-if="item.evidence.length"
              class="evidence-list"
            >
              <li
                v-for="evidence in item.evidence"
                :key="evidence.evidenceId"
              >
                <span><strong :title="evidence.title">{{ evidence.title }}</strong><small :title="evidence.excerpt">{{ evidence.excerpt }}</small></span>
                <button
                  type="button"
                  @click="addSource(index, evidence)"
                >
                  选用来源
                </button>
              </li>
            </ul>
          </div>
        </div>
        <div
          v-if="canEditRequirements"
          class="editor-actions"
        >
          <button
            class="button button-secondary"
            type="button"
            @click="requirementDrafts.push(blankRequirement()); dirtyRequirements = true"
          >
            添加需求
          </button>
          <label class="reason-field">修改原因<input
            v-model="changeReason"
            name="change-reason"
            placeholder="说明本次修订原因"
            @input="dirtyRequirements = true"
          ></label>
          <button
            class="button button-primary"
            data-action="save-requirements"
            type="button"
            :disabled="saving || !changeReason.trim() || dirtyOutline"
            @click="saveRequirements"
          >
            {{ saving ? '正在保存…' : '保存需求新版本' }}
          </button>
        </div>
        <div
          v-if="canConfirmRequirements && solution.requirementVersion"
          class="confirm-block"
        >
          <span :title="solution.requirementVersion.id">即将确认需求 V{{ solution.requirementVersion.number }} · {{ solution.requirementVersion.id }}</span>
          <button
            class="button button-primary"
            data-action="confirm-requirements"
            type="button"
            :disabled="saving || dirty"
            @click="confirmRequirements"
          >
            确认需求 V{{ solution.requirementVersion.number }}
          </button>
        </div>
      </section>

      <section
        class="step-card"
        :inert="saving ? true : undefined"
        aria-labelledby="outline-heading"
      >
        <div class="step-heading">
          <div>
            <p class="eyebrow">
              步骤 2
            </p><h3 id="outline-heading">
              方案大纲
            </h3>
          </div>
          <span
            v-if="solution.outlineVersion"
            class="version-tag"
            :title="solution.outlineVersion.id"
          >V{{ solution.outlineVersion.number }}</span>
        </div>
        <p
          v-if="!solution.requirementConfirmation?.valid"
          class="step-note"
        >
          确认需求后可编辑大纲。
        </p>
        <template v-else>
          <div
            v-if="solution.outlineConfirmation"
            class="confirmation"
            :class="{ invalid: !solution.outlineConfirmation.valid }"
          >
            {{ solution.outlineConfirmation.valid ? '已确认' : '确认已失效' }} · 版本 {{ solution.outlineConfirmation.versionId }} · {{ solution.outlineConfirmation.confirmedBy }}
          </div>
          <div
            v-for="(node, index) in outlineDrafts"
            :key="index"
            class="item-card"
          >
            <div class="item-heading">
              <strong>章节 {{ index + 1 }}</strong><div
                v-if="canEditOutline"
                class="reorder-actions"
              >
                <button
                  type="button"
                  :disabled="index === 0"
                  @click="moveOutline(index, -1)"
                >
                  上移
                </button>
                <button
                  type="button"
                  :disabled="index === outlineDrafts.length - 1"
                  @click="moveOutline(index, 1)"
                >
                  下移
                </button>
                <button
                  v-if="outlineDrafts.length > 1"
                  type="button"
                  @click="removeOutline(index)"
                >
                  移除
                </button>
              </div>
            </div>
            <div class="field-grid outline-grid">
              <label>章节标题<input
                v-model="node.title"
                :name="`outline-title-${index}`"
                :readonly="!canEditOutline"
                @input="dirtyOutline = true"
              ></label>
              <label>层级<select
                v-model.number="node.level"
                :disabled="!canEditOutline"
                @change="dirtyOutline = true"
              >
                <option
                  v-for="level in 4"
                  :key="level"
                  :value="level"
                >{{ level }} 级</option>
              </select></label>
              <label class="required-check"><input
                v-model="node.required"
                type="checkbox"
                :disabled="!canEditOutline"
                @change="dirtyOutline = true"
              >必填章节</label>
              <label>章节目标<textarea
                v-model="node.objective"
                rows="2"
                :readonly="!canEditOutline"
                @input="dirtyOutline = true"
              /></label>
              <label>检索提示<textarea
                v-model="node.retrievalHint"
                rows="2"
                :readonly="!canEditOutline"
                @input="dirtyOutline = true"
              /></label>
            </div>
            <fieldset
              class="risk-options"
              :disabled="!canEditOutline"
            >
              <legend>风险类型</legend>
              <label
                v-for="risk in risks"
                :key="risk.value"
              ><input
                v-model="node.riskTypes"
                type="checkbox"
                :value="risk.value"
                @change="dirtyOutline = true"
              >{{ risk.label }}</label>
            </fieldset>
          </div>
          <div
            v-if="canEditOutline"
            class="editor-actions"
          >
            <button
              class="button button-secondary"
              type="button"
              @click="outlineDrafts.push(blankOutline(outlineDrafts.length + 1)); dirtyOutline = true"
            >
              添加章节
            </button>
            <label class="reason-field">修改原因<input
              v-model="outlineChangeReason"
              name="outline-change-reason"
              placeholder="说明本次大纲修订原因"
              @input="dirtyOutline = true"
            ></label>
            <button
              class="button button-primary"
              data-action="save-outline"
              type="button"
              :disabled="saving || !outlineChangeReason.trim() || dirtyRequirements"
              @click="saveOutline"
            >
              {{ saving ? '正在保存…' : '保存大纲新版本' }}
            </button>
          </div>
          <div
            v-if="canConfirmOutline && solution.outlineVersion"
            class="confirm-block"
          >
            <span :title="solution.outlineVersion.id">即将确认大纲 V{{ solution.outlineVersion.number }} · {{ solution.outlineVersion.id }}</span>
            <button
              class="button button-primary"
              data-action="confirm-outline"
              type="button"
              :disabled="saving || dirty"
              @click="confirmOutline"
            >
              确认大纲 V{{ solution.outlineVersion.number }}
            </button>
          </div>
        </template>
      </section>
    </template>
  </section>
</template>

<style scoped>
.solution-detail { display: grid; gap: 20px; min-width: 0; }.back-link { justify-self: start; color: var(--color-accent); font-weight: 750; text-decoration: none; }
.step-card[inert] { opacity: .72; }
.detail-header, .step-card { min-width: 0; padding: 24px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
.detail-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 20px; }.header-copy { min-width: 0; }.header-copy h2 { margin: 4px 0 8px; overflow-wrap: anywhere; font-size: clamp(25px, 3vw, 35px); }.header-copy p:last-child { margin: 0; overflow-wrap: anywhere; color: var(--color-muted); font-family: var(--font-mono); font-size: 12px; }
.eyebrow { margin: 0; color: var(--color-accent); font-size: 12px; font-weight: 800; letter-spacing: .08em; }.header-meta { display: grid; justify-items: end; gap: 8px; flex: 0 0 auto; color: var(--color-muted); font-size: 12px; }.phase-tag, .version-tag { padding: 6px 10px; border-radius: 999px; color: #126456; background: #e4f3f1; font-size: 12px; font-weight: 750; }
.step-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }.step-heading h3 { margin: 4px 0 0; font-size: 22px; }.step-note { color: var(--color-muted); font-size: 13px; }.confirmation { margin: 12px 0; padding: 11px 14px; overflow-wrap: anywhere; border-radius: 9px; color: #205d4e; background: #eaf7ef; font-size: 13px; }.confirmation.invalid { color: #8b4b0c; background: #fff3db; }
.item-card { display: grid; gap: 14px; min-width: 0; margin-top: 15px; padding: 18px; border: 1px solid var(--color-border-soft); border-radius: 12px; background: #fbfcfd; }.item-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }.item-heading button, .source-area button, .reorder-actions button { min-height: 32px; padding: 4px 9px; border: 1px solid var(--color-border); border-radius: 7px; color: var(--color-primary); background: #fff; font: inherit; font-size: 12px; font-weight: 750; cursor: pointer; }.reorder-actions { display: flex; gap: 6px; flex-wrap: wrap; }
.field-grid { display: grid; grid-template-columns: minmax(160px, 230px) minmax(0, 1fr); gap: 14px; min-width: 0; }.field-grid label, .source-picker label, .reason-field { display: grid; gap: 6px; min-width: 0; color: var(--color-muted); font-size: 13px; font-weight: 750; }.wide-field { min-width: 0; } input:not([type=checkbox]), select, textarea { width: 100%; min-height: 42px; padding: 9px 11px; border: 1px solid var(--color-border); border-radius: var(--radius-control); color: var(--color-ink); background: #fff; font: inherit; }.field-grid textarea { resize: vertical; }
.source-area { display: grid; gap: 10px; min-width: 0; }.source-area > strong { font-size: 13px; }.source-area ul { margin: 0; padding: 0; list-style: none; }.source-area li { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; min-width: 0; padding: 7px 0; border-top: 1px solid var(--color-border-soft); }.source-area li span, .source-area li small { max-width: 100%; min-width: 0; overflow-wrap: anywhere; }.source-area li small { color: var(--color-muted); }
.source-picker { display: flex; align-items: end; gap: 8px; flex-wrap: wrap; }.source-picker label { flex: 1 1 250px; }.evidence-list li > span { display: grid; min-width: 0; flex: 1 1 230px; }.evidence-list strong, .evidence-list small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.evidence-list small { color: var(--color-muted); }
.editor-actions, .confirm-block { display: flex; align-items: end; justify-content: flex-end; flex-wrap: wrap; gap: 12px; margin-top: 17px; }.reason-field { flex: 1 1 260px; }.confirm-block { padding-top: 15px; border-top: 1px solid var(--color-border-soft); }.confirm-block span { min-width: 0; flex: 1 1 260px; overflow-wrap: anywhere; color: var(--color-muted); font-size: 13px; }
.button { min-height: 44px; padding: 0 18px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); appearance: none; font: inherit; font-weight: 750; cursor: pointer; }.button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; }.button-primary:hover { border-color: var(--color-primary-strong); background: var(--color-primary-strong); }.button-secondary { color: var(--color-primary); background: #fff; }.button:disabled, button:disabled { opacity: .52; cursor: not-allowed; }
.outline-grid { grid-template-columns: minmax(0, 1fr) 120px 120px; }.outline-grid label:nth-child(4), .outline-grid label:nth-child(5) { grid-column: span 2; }.required-check { display: flex !important; align-items: center; gap: 8px; }.risk-options { display: flex; gap: 8px 16px; flex-wrap: wrap; margin: 0; padding: 10px; border: 1px solid var(--color-border-soft); border-radius: 8px; }.risk-options legend { color: var(--color-muted); font-size: 13px; font-weight: 750; }.risk-options label { display: flex; gap: 5px; align-items: center; font-size: 12px; }
.state { padding: 20px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); color: var(--color-muted); background: #fff; }.state-error { color: #a51f32; background: #fff8f8; }.state-warning { color: #86500a; background: #fff8e7; }.state button { margin-left: 8px; min-height: 34px; border: 1px solid var(--color-border); border-radius: 7px; color: var(--color-primary); background: #fff; font: inherit; cursor: pointer; }
button:focus-visible, a:focus-visible, input:focus-visible, select:focus-visible, textarea:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
@media (max-width: 850px) { .outline-grid { grid-template-columns: minmax(0, 1fr) 100px; }.outline-grid label:nth-child(3) { grid-column: 1 / -1; }.outline-grid label:nth-child(4), .outline-grid label:nth-child(5) { grid-column: 1 / -1; } }
@media (max-width: 620px) { .detail-header { flex-direction: column; }.header-meta { justify-items: start; }.field-grid, .outline-grid { grid-template-columns: 1fr; }.outline-grid label:nth-child(3), .outline-grid label:nth-child(4), .outline-grid label:nth-child(5) { grid-column: auto; }.item-card, .detail-header, .step-card { padding: 16px; }.editor-actions, .confirm-block { align-items: stretch; flex-direction: column; }.reason-field { flex: 0 1 auto; width: 100%; }.editor-actions .button, .confirm-block .button { width: 100%; } }
</style>
