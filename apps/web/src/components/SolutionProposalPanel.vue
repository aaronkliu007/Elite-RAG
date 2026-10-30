<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ApiRequestError } from '../api/http'
import { solutionsApi, type ProposalKind, type SolutionProposal } from '../api/solutions'

const props = defineProps<{ actorId: string, solutionId: string, kind: ProposalKind, revision: number,
  currentTexts: string[], disabled: boolean }>()
const emit = defineEmits<{ adopted: [], busyChange: [value: boolean] }>()
const attempt = ref<SolutionProposal | null>(null)
const busy = ref(false)
const reviewed = ref(false)
const error = ref('')
const clientKey = ref('')
const adoptionKey = ref('')
let disposed = false
const storageKey = `solution-proposal:${props.actorId}:${props.solutionId}:${props.kind}:${props.revision}`
const label = computed(() => props.kind === 'requirements' ? '需求整理' : '大纲')
const categoryLabels: Record<string, string> = { GOAL: '目标', SCOPE: '范围', CONSTRAINT: '约束',
  DELIVERABLE: '交付物', STAKEHOLDER: '相关方', DATE: '时间', RISK: '风险', UNKNOWN: '待确认' }
const riskLabels: Record<string, string> = { AMOUNT_OR_PRICE: '金额或价格', DATE_OR_DURATION: '日期或周期',
  PERFORMANCE_OR_SLA: '性能或服务级别', CUSTOMER_OR_CASE_FACT: '客户或案例事实',
  COMPANY_OR_PRODUCT_CAPABILITY: '公司或产品能力', LEGAL_OR_COMPLIANCE_COMMITMENT: '法律或合规承诺' }
const texts = computed(() => {
  const result = attempt.value?.result
  return result?.kind === 'REQUIREMENTS' ? result.items.map(item => item.text)
    : result?.kind === 'OUTLINE' ? result.nodes.map(node => `${node.order}. ${node.title}：${node.objective}`) : []
})
const status = computed(() => ({ RUNNING: '生成中，请查询原请求', SUCCEEDED: '候选待人工审阅',
  FAILED: '生成失败，请检查输入或联系管理员', INDETERMINATE: '结果不确定，请联系管理员核查后再发起新请求' })[attempt.value?.status ?? 'RUNNING'])
const canAdopt = computed(() => !props.disabled && !busy.value && reviewed.value
  && attempt.value?.status === 'SUCCEEDED' && !!attempt.value.result)

function fail(failure: unknown) {
  error.value = failure instanceof ApiRequestError ? failure.displayMessage : '操作失败，当前候选已保留'
}
function setBusy(value: boolean) { busy.value = value; emit('busyChange', value) }
async function generate() {
  if (props.disabled || busy.value || attempt.value) return
  clientKey.value ||= crypto.randomUUID()
  sessionStorage.setItem(storageKey, clientKey.value)
  setBusy(true)
  error.value = ''
  try {
    const result = await solutionsApi.generateProposal(props.solutionId, props.kind, props.revision, clientKey.value)
    if (!disposed) attempt.value = result
  } catch (failure) {
    if (!disposed) {
      fail(failure)
      if (failure instanceof ApiRequestError && failure.code === 'MODEL_PROPOSAL_UNAVAILABLE') {
        clientKey.value = ''
        sessionStorage.removeItem(storageKey)
      }
    }
  }
  finally { setBusy(false) }
}
async function refresh() {
  if (!clientKey.value || busy.value) return
  setBusy(true)
  error.value = ''
  try {
    const result = await solutionsApi.getProposal(props.solutionId, props.kind, props.revision, clientKey.value)
    if (!disposed) attempt.value = result
  } catch (failure) {
    if (!disposed) {
      fail(failure)
      if (failure instanceof ApiRequestError && [404, 409, 422].includes(failure.status)) {
        attempt.value = null
        reviewed.value = false
      }
    }
  }
  finally { setBusy(false) }
}
async function adopt() {
  const result = attempt.value?.result
  if (!canAdopt.value || !result) return
  adoptionKey.value ||= crypto.randomUUID()
  setBusy(true)
  error.value = ''
  try {
    if (result.kind === 'REQUIREMENTS') await solutionsApi.reviseRequirements(props.solutionId,
      result.items, '采纳模型需求整理候选', props.revision, adoptionKey.value)
    else await solutionsApi.reviseOutline(props.solutionId, result.nodes,
      '采纳模型大纲候选', props.revision, adoptionKey.value)
    if (!disposed) {
      sessionStorage.removeItem(storageKey)
      emit('adopted')
    }
  } catch (failure) { if (!disposed) fail(failure) }
  finally { setBusy(false) }
}
onMounted(() => {
  clientKey.value = sessionStorage.getItem(storageKey) ?? ''
  if (clientKey.value) void refresh()
})
onUnmounted(() => { disposed = true })
</script>

<template>
  <section
    class="proposal-panel"
    :aria-label="`${label}候选`"
    :aria-busy="busy"
  >
    <h4>{{ label }}候选</h4>
    <p>基于已保存的需求生成建议，审阅后可采纳为待确认修订。{{ kind === 'requirements' ? '仅整理已有需求及来源。' : '以当前已确认需求为依据。' }}</p>
    <p
      v-if="disabled && !busy"
      class="notice"
    >
      请先保存或处理当前编辑内容，再操作候选。
    </p>
    <div class="actions">
      <button
        class="button button-primary"
        type="button"
        data-action="generate-proposal"
        :disabled="disabled || busy || !!attempt"
        @click="generate"
      >
        {{ busy ? '处理中…' : clientKey ? '重发原请求（沿用原键）' : `生成${label}候选` }}
      </button>
      <button
        v-if="clientKey"
        class="button button-secondary"
        type="button"
        :disabled="busy"
        data-action="refresh-proposal"
        @click="refresh"
      >
        查询原请求
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
      {{ status }}
    </p>
    <div
      v-if="attempt?.status === 'SUCCEEDED' && attempt.result"
      class="comparison"
    >
      <div>
        <h5>当前内容</h5><ol>
          <li
            v-for="(text, index) in currentTexts"
            :key="index"
          >
            {{ text }}
          </li>
        </ol>
      </div>
      <div>
        <h5>候选内容</h5><ol>
          <li
            v-for="(text, index) in texts"
            :key="index"
          >
            {{ text }}
          </li>
        </ol>
        <ul
          v-if="attempt.result.kind === 'REQUIREMENTS'"
          class="sources"
        >
          <li
            v-for="(item, index) in attempt.result.items"
            :key="index"
          >
            需求 {{ index + 1 }} · {{ categoryLabels[item.category] }} · {{ item.sources.length ? `${item.sources.length} 项原有来源` : '待明确，无来源' }}
            <span
              v-for="(source, sourceIndex) in item.sources"
              :key="sourceIndex"
              class="source-ref"
            >
              文档 {{ source.documentId }} · 版本 {{ source.versionId }} ·
              {{ source.locator.type === 'PAGE' ? `第 ${source.locator.pageNumber} 页`
                : source.locator.type === 'SLIDE' ? `第 ${source.locator.slideNumber} 张幻灯片`
                  : source.locator.type === 'TABLE' ? `${source.locator.tableName} 第 ${source.locator.startRow}–${source.locator.endRow} 行`
                    : source.locator.headingPath.join(' / ') }}
            </span>
          </li>
        </ul>
        <ul v-else-if="attempt.result.kind === 'OUTLINE'">
          <li
            v-for="(node, index) in attempt.result.nodes"
            :key="index"
          >
            章节 {{ node.order }} · 第 {{ node.level }} 级 · {{ node.required ? '必填' : '选填' }}
            <p>检索提示：{{ node.retrievalHint }}</p>
            <p>风险：{{ node.riskTypes.map(risk => riskLabels[risk]).join('、') || '无预设风险' }}</p>
          </li>
        </ul>
      </div>
    </div>
    <div
      v-if="attempt?.status === 'SUCCEEDED'"
      class="adoption"
    >
      <label><input
        v-model="reviewed"
        type="checkbox"
        :disabled="busy || disabled"
      > 我已审阅候选内容和来源，知晓仍需异人确认</label>
      <button
        class="button button-primary"
        type="button"
        data-action="adopt-proposal"
        :disabled="!canAdopt"
        @click="adopt"
      >
        采纳为待确认修订
      </button>
    </div>
  </section>
</template>

<style scoped>
.proposal-panel { margin: 18px 0; padding: 18px; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: var(--color-surface, #fff); min-width: 0; }
h4, h5 { margin: 0 0 10px; } p, li, .source-ref { overflow-wrap: anywhere; } p { line-height: 1.6; }
.actions, .adoption { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.adoption { margin-top: 16px; } .adoption label { display: flex; align-items: flex-start; gap: 8px; min-width: 0; }
.comparison { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 20px; margin-top: 16px; }
.comparison > div { min-width: 0; padding: 14px; background: var(--color-background, #f5f7fa); border-radius: var(--radius-control); }
ol, ul { padding-left: 22px; } li { margin: 8px 0; white-space: pre-wrap; line-height: 1.6; }.source-ref { display: block; font-size: 12px; color: var(--color-text-muted); }.notice { color: #8a3d0a; }
.button { min-height: 44px; padding: 0 16px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); appearance: none; font: inherit; font-weight: 750; cursor: pointer; }
.button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; }.button-primary:hover:not(:disabled) { background: var(--color-primary-strong); border-color: var(--color-primary-strong); }.button-secondary { color: var(--color-primary); background: #fff; }.button:disabled { opacity: .52; cursor: not-allowed; }button:focus-visible, input:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
@media (max-width: 760px) { .comparison { grid-template-columns: minmax(0, 1fr); }.actions .button, .adoption .button { width: 100%; } }
</style>
