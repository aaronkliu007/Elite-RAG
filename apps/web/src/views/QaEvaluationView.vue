<script setup lang="ts">
import { computed, ref } from 'vue'
import { ApiRequestError, apiRequest } from '../api/http'
import QaEvaluationReviewPanel from '../components/QaEvaluationReviewPanel.vue'

interface CaseResult {
  caseId: string; requestId: string; expectedRefusal: boolean; status: string; reason: string | null
  expectedEvidence: number; retrievedEvidence: number; generatedClaims: number
  reviewedClaims: number; supportedClaims: number; leakageCount: number
}
interface Assessment { passed: boolean; failures: string[] }
interface EvaluationRun {
  runId: string; datasetVersion: string; assessment: Assessment; historicalAssessment: Assessment
  caseResults: CaseResult[]
}
const runId = ref('')
const result = ref<EvaluationRun | null>(null)
const loading = ref(false)
const error = ref('')
const filter = ref('all')
const reviewCaseId = ref('')
const visibleCases = computed(() => (result.value?.caseResults ?? []).filter(item => {
  if (filter.value === 'unexpected-refusal') return !item.expectedRefusal && item.status === 'REFUSED'
  if (filter.value === 'review') return item.reviewedClaims < item.generatedClaims
  if (filter.value === 'failed') return item.status === 'FAILED'
  return true
}))
const reasons: Record<string, string> = {
  CONFLICTING_EVIDENCE: '证据冲突', INSUFFICIENT_EVIDENCE: '证据不足',
  NO_AUTHORIZED_SPACE: '没有已授权空间', UNSAFE_TO_SEND: '内容未通过安全检查',
  INVALID_MODEL_OUTPUT: '模型输出无效', UNSUPPORTED_CLAIM: '声明缺少支持',
}
const failures: Record<string, string> = {
  LEAKAGE: '越权泄漏', CITATION_VALIDITY: '引用有效率', RECALL_AT_10: '证据召回率',
  CLAIM_SUPPORT: '声明支持率', CLAIM_REVIEW_COVERAGE: '声明审核覆盖率',
  CORRECT_REFUSAL: '正确拒答率', DATASET_PROVENANCE: '数据集内容未匹配',
}
async function load() {
  if (!runId.value.trim() || loading.value) return
  loading.value = true
  error.value = ''
  result.value = null
  reviewCaseId.value = ''
  try {
    result.value = await apiRequest<EvaluationRun>(`/api/admin/qa/evaluation-runs/${encodeURIComponent(runId.value.trim())}`)
  } catch (failure) {
    error.value = failure instanceof ApiRequestError ? failure.displayMessage : '查询失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}
function resultText(item: CaseResult) {
  if (item.status === 'ANSWERED') return '已回答'
  if (item.status === 'REFUSED') return `已拒答：${reasons[item.reason ?? ''] ?? '其他原因'}`
  return '系统处理失败'
}
</script>

<template>
  <section class="evaluation-view">
    <header>
      <p class="eyebrow">
        系统管理
      </p><h2>问答评测结果</h2><p>查看质量门禁和逐案例诊断，结果不包含问题、回答或证据正文。</p>
    </header>
    <form
      class="query-card"
      @submit.prevent="load"
    >
      <label>评测运行编号<input
        v-model.trim="runId"
        name="runId"
        autocomplete="off"
        required
        placeholder="输入评测运行编号"
      ></label>
      <button
        class="button button-primary"
        type="submit"
        :disabled="loading || !runId.trim()"
      >
        {{ loading ? '查询中…' : '查询结果' }}
      </button>
    </form>
    <p
      v-if="error"
      class="state error"
      role="alert"
    >
      {{ error }}
    </p>
    <p
      v-else-if="loading"
      class="state"
      role="status"
    >
      正在读取评测结果…
    </p>
    <template v-else-if="result">
      <section
        class="summary"
        aria-label="评测门禁"
      >
        <h3>{{ result.assessment.passed ? '当前门禁通过' : '当前门禁未通过' }}</h3>
        <p>数据集版本：{{ result.datasetVersion }}</p>
        <p v-if="result.assessment.failures.length">
          未通过项：{{ result.assessment.failures.map(f => failures[f] ?? f).join('、') }}
        </p>
        <p>历史判断：{{ result.historicalAssessment.passed ? '通过' : '未通过' }}；当前判断按现行规则计算。</p>
      </section>
      <section
        class="cases"
        aria-label="逐案例诊断"
      >
        <h3>逐案例诊断</h3>
        <label v-if="result.caseResults?.length">筛选案例<select
          v-model="filter"
          name="filter"
        >
          <option value="all">全部案例</option>
          <option value="unexpected-refusal">预期回答但拒答</option>
          <option value="review">有未审核声明</option>
          <option value="failed">系统处理失败</option>
        </select></label>
        <p
          v-if="result.caseResults?.length && !visibleCases.length"
          class="state"
        >
          没有符合筛选条件的案例。
        </p>
        <p
          v-if="!result.caseResults?.length"
          class="state"
        >
          此记录没有逐案例诊断；旧评测记录不会自动补齐。
        </p>
        <article
          v-for="item in visibleCases"
          :key="item.caseId"
          class="case-card"
        >
          <div class="case-heading">
            <strong>{{ resultText(item) }}</strong><span>{{ item.expectedRefusal ? '预期拒答' : '预期回答' }}</span>
          </div>
          <p
            class="identifier"
            :title="item.caseId"
          >
            案例编号：{{ item.caseId }}
          </p>
          <p
            class="identifier"
            :title="item.requestId"
          >
            问答请求编号：{{ item.requestId }}
          </p>
          <p>期望证据召回 {{ item.retrievedEvidence }}/{{ item.expectedEvidence }} · 声明审核 {{ item.reviewedClaims }}/{{ item.generatedClaims }} · 已支持 {{ item.supportedClaims }} · 越权 {{ item.leakageCount }}</p>
          <button
            v-if="item.status === 'ANSWERED' && !item.expectedRefusal"
            class="review-action"
            type="button"
            data-action="load-review"
            @click="reviewCaseId = item.caseId"
          >
            审核声明
          </button>
          <QaEvaluationReviewPanel
            v-if="reviewCaseId === item.caseId"
            :key="item.caseId"
            :run-id="result.runId"
            :case-id="item.caseId"
          />
        </article>
      </section>
    </template>
    <p
      v-else
      class="state"
    >
      输入评测运行编号后查询。只有系统管理员可查看。
    </p>
  </section>
</template>

<style scoped>
.evaluation-view { display: grid; gap: 18px; max-width: 1120px; margin: 0 auto; min-width: 0; }
header h2, h3, p { margin: 0; } header { display: grid; gap: 6px; } header p { color: var(--color-muted); }
.query-card, .summary, .cases { display: grid; gap: 12px; min-width: 0; padding: 22px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: white; }
.query-card { grid-template-columns: minmax(0, 1fr) auto; align-items: end; }
label { display: grid; gap: 8px; min-width: 0; font-weight: 700; } input, select { min-width: 0; width: 100%; min-height: 44px; padding: 8px 12px; border: 1px solid var(--color-border); border-radius: var(--radius-control); }
.button { min-height: 44px; padding: 0 22px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); appearance: none; font-weight: 800; cursor: pointer; }
.button-primary { color: white; background: var(--color-primary); -webkit-text-fill-color: white; opacity: 1; }
.button-primary:hover { background: var(--color-primary-strong); }.button:focus-visible, input:focus-visible { outline: 3px solid #89b4f8; outline-offset: 2px; }.button:disabled { opacity: .5; cursor: not-allowed; }
.summary p, .case-card p { color: var(--color-muted); overflow-wrap: anywhere; }.state { padding: 18px; color: var(--color-muted); }.error { color: #a11a1a; }
.review-action { justify-self: start; min-height: 38px; padding: 0 14px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); color: var(--color-primary); background: white; font-weight: 700; cursor: pointer; } .review-action:focus-visible { outline: 3px solid #89b4f8; outline-offset: 2px; }
.case-card { display: grid; gap: 8px; min-width: 0; padding: 16px; border: 1px solid var(--color-border); border-radius: var(--radius-control); }
.case-heading { display: flex; gap: 12px; flex-wrap: wrap; justify-content: space-between; }.identifier { font-size: .9rem; }
@media (max-width: 600px) { .query-card { grid-template-columns: 1fr; } .button { width: 100%; } }
</style>
