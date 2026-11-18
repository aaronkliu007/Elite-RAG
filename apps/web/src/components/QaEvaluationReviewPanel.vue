<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ApiRequestError, apiRequest } from '../api/http'
import QaCitationList from './QaCitationList.vue'

interface ReviewedEvidence { documentId: string; versionId: string; nodeId: string; contentDigest: string; titleDigest: string; locatorDigest: string }
interface ReviewClaim { claimId: string; text: string; evidenceIds: string[]; supportingEvidence: ReviewedEvidence[]; eligible: boolean }
interface ReviewContext { caseId: string; requestId: string; question: string; reviewQuestionDigest: string; claims: ReviewClaim[] }
const props = defineProps<{ runId: string, caseId: string }>()
const context = ref<ReviewContext | null>(null)
const loading = ref(false)
const error = ref('')
const draft = ref('')
const references = reactive<Record<string, string>>({})
const verdicts = reactive<Record<string, string>>({})
const relevance = reactive<Record<string, string>>({})
const url = () => `/api/admin/qa/evaluation-runs/${encodeURIComponent(props.runId)}/cases/${encodeURIComponent(props.caseId)}/review-context`
function failureMessage(failure: unknown) {
  return failure instanceof ApiRequestError ? failure.displayMessage : '审核内容暂不可用，请检查本人权限和评测版本。'
}
async function load() {
  loading.value = true
  error.value = ''
  draft.value = ''
  try {
    context.value = await apiRequest<ReviewContext>(url())
    for (const claim of context.value.claims) {
      verdicts[claim.claimId] ??= ''
      relevance[claim.claimId] ??= ''
    }
  }
  catch (failure) { context.value = null; error.value = failureMessage(failure) }
  finally { loading.value = false }
}
async function prepare(claim: ReviewClaim) {
  const reviewReference = references[claim.claimId]?.trim() ?? ''
  const verdict = verdicts[claim.claimId] ?? ''
  const questionRelevance = relevance[claim.claimId] ?? ''
  if (!['RELEVANT', 'IRRELEVANT'].includes(questionRelevance)) return
  if (!context.value || !claim.eligible || !reviewReference || !['SUPPORTED', 'UNSUPPORTED'].includes(verdict) || loading.value) return
  loading.value = true
  error.value = ''
  draft.value = ''
  try {
    const fresh = await apiRequest<ReviewContext>(url())
    const current = fresh.claims.find(item => item.claimId === claim.claimId)
    if (!current?.eligible || fresh.reviewQuestionDigest !== context.value.reviewQuestionDigest
      || JSON.stringify(current) !== JSON.stringify(claim)) throw new Error('changed')
    draft.value = JSON.stringify({ reviewQuestionDigest: fresh.reviewQuestionDigest, reviewedClaims: [{
      text: current.text, supportingEvidence: current.supportingEvidence, reviewReference, verdict, questionRelevance,
    }] }, null, 2)
    context.value = fresh
  } catch (failure) {
    context.value = null
    error.value = failureMessage(failure)
  } finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <section
    class="review-panel"
    aria-label="声明人工审核"
  >
    <h3>声明人工审核</h3>
    <p>请逐条查看来源并独立判断。生成的片段需要人工合并到黄金集；系统不会自动认定审核真实发生。</p>
    <p
      v-if="loading"
      role="status"
    >
      正在验证审核权限…
    </p>
    <p
      v-if="error"
      class="error"
      role="alert"
    >
      {{ error }}
    </p>
    <template v-if="context">
      <p class="question">
        问题：{{ context.question }}
      </p>
      <QaCitationList
        :request-id="context.requestId"
        :citations="context.claims"
      />
      <article
        v-for="(claim, index) in context.claims"
        :key="claim.claimId"
        class="review-claim"
      >
        <strong>声明 {{ index + 1 }}</strong>
        <p
          v-if="!claim.eligible"
          class="error"
        >
          该声明引用了未列为期望来源的材料，请先核对黄金集，不能生成标注。
        </p>
        <template v-else>
          <label>审核记录标识<input
            v-model.trim="references[claim.claimId]"
            name="reviewReference"
            maxlength="128"
            autocomplete="off"
          ></label>
          <label>来源是否支持<select
            v-model="verdicts[claim.claimId]"
            name="verdict"
          >
            <option value="">请选择</option><option value="SUPPORTED">支持</option><option value="UNSUPPORTED">不支持</option>
          </select></label>
          <label>是否针对问题<select
            v-model="relevance[claim.claimId]"
            name="questionRelevance"
          >
            <option value="">请选择</option><option value="RELEVANT">针对问题</option><option value="IRRELEVANT">与问题无关</option>
          </select></label>
          <button
            class="button button-primary"
            type="button"
            data-action="prepare-label"
            :disabled="loading || !references[claim.claimId]?.trim() || !verdicts[claim.claimId] || !relevance[claim.claimId]"
            @click="prepare(claim)"
          >
            生成标注片段
          </button>
        </template>
      </article>
      <label v-if="draft">标注片段（复制并人工合并到黄金集）<textarea
        name="reviewDraft"
        :value="draft"
        readonly
        rows="10"
      /></label>
    </template>
  </section>
</template>

<style scoped>
.review-panel, .review-claim { display: grid; gap: 12px; min-width: 0; padding: 18px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; }
h3, p { margin: 0; overflow-wrap: anywhere; }.question { font-weight: 700; }.error { color: #a11a1a; }
label { display: grid; gap: 6px; min-width: 0; font-weight: 700; } input, select, textarea { width: 100%; min-width: 0; padding: 10px; border: 1px solid var(--color-border); border-radius: var(--radius-control); font: inherit; }
textarea { resize: vertical; overflow-wrap: anywhere; }.button { min-height: 44px; padding: 0 18px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); appearance: none; font-weight: 800; cursor: pointer; }
.button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; opacity: 1; }.button-primary:hover { background: var(--color-primary-strong); }.button:disabled { opacity: .5; cursor: not-allowed; }
.button:focus-visible, input:focus-visible, select:focus-visible, textarea:focus-visible { outline: 3px solid #89b4f8; outline-offset: 2px; }
</style>
