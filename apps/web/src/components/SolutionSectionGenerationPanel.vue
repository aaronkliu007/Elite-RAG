<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

import { ApiRequestError } from '../api/http'
import { solutionsApi, type OutlineNode, type SectionGenerationAttempt } from '../api/solutions'

const props = defineProps<{
  solutionId: string, section: OutlineNode, revision: number,
  baselineVersionId: string | null, canGenerate: boolean, disabled: boolean
}>()
const emit = defineEmits<{ adopted: [], reload: [] }>()
const attempt = ref<SectionGenerationAttempt | null>(null)
const clientKey = ref('')
const adoptionKey = ref('')
const busy = ref(false)
const error = ref('')
const conflict = ref(false)
const reviewed = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false

const storageKey = computed(() => `solution-generation:${props.solutionId}:${props.section.sectionId}`)
const active = computed(() => attempt.value != null &&
  ['PENDING', 'RETRIEVING', 'GENERATING', 'VERIFYING'].includes(attempt.value.status))
const blocking = computed(() => attempt.value?.sectionVersion?.verification.verifications.some(
  (item) => item.verdict === 'PARTIAL' || item.verdict === 'UNSUPPORTED') ?? false)
const canAdopt = computed(() => props.canGenerate && !props.disabled && !busy.value && reviewed.value &&
  attempt.value?.status === 'SUCCEEDED' && !!attempt.value.sectionVersion && !blocking.value)
const statusLabel: Record<SectionGenerationAttempt['status'], string> = {
  PENDING: '排队中', RETRIEVING: '检索证据中', GENERATING: '生成中', VERIFYING: '核验证据中',
  SUCCEEDED: '草稿待人工审核', INSUFFICIENT_EVIDENCE: '证据不足', FAILED_RETRYABLE: '暂时失败，可重试',
  FAILED_MANUAL: '需要人工处理', CANCELLED: '已取消',
}
const verdictLabel = { SUPPORTED: '已支持', HUMAN_CONFIRMATION_REQUIRED: '需人工确认',
  PARTIAL: '部分支持，禁止采纳', UNSUPPORTED: '不支持，禁止采纳' }

function clearTimer() {
  if (timer) clearTimeout(timer)
  timer = undefined
}

function clearAttempt() {
  clearTimer()
  attempt.value = null
  clientKey.value = ''
  adoptionKey.value = ''
  reviewed.value = false
  sessionStorage.removeItem(storageKey.value)
}

function schedulePoll() {
  clearTimer()
  if (active.value && !disposed) timer = setTimeout(() => { void refreshAttempt() }, 2500)
}

function fail(failure: unknown, fallback: string) {
  error.value = failure instanceof ApiRequestError ? failure.displayMessage : fallback
  conflict.value = failure instanceof ApiRequestError && [409, 422].includes(failure.status)
}

async function refreshAttempt() {
  if (!clientKey.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    attempt.value = await solutionsApi.getSectionAttempt(props.solutionId, props.section.sectionId, clientKey.value)
  } catch (failure) {
    fail(failure, '读取生成状态失败，请重试')
    if (failure instanceof ApiRequestError && [404, 409, 422].includes(failure.status)) {
      clearAttempt()
    }
  } finally {
    busy.value = false
    schedulePoll()
  }
}

async function generate() {
  if (!props.canGenerate || props.disabled || busy.value) return
  if (!clientKey.value || attempt.value?.status !== 'FAILED_RETRYABLE') {
    clearAttempt()
    clientKey.value = crypto.randomUUID()
  }
  sessionStorage.setItem(storageKey.value, clientKey.value)
  reviewed.value = false
  busy.value = true
  error.value = ''
  conflict.value = false
  try {
    attempt.value = await solutionsApi.generateSection(props.solutionId, props.section.sectionId,
      props.revision, props.baselineVersionId, clientKey.value)
  } catch (failure) {
    fail(failure, '生成失败，请稍后重试')
    if (failure instanceof ApiRequestError && [404, 409, 422].includes(failure.status)) clearAttempt()
  } finally {
    busy.value = false
    schedulePoll()
  }
}

async function adopt() {
  if (!canAdopt.value || !attempt.value?.sectionVersion) return
  adoptionKey.value ||= crypto.randomUUID()
  busy.value = true
  error.value = ''
  conflict.value = false
  try {
    await solutionsApi.adoptSection(props.solutionId, props.section.sectionId,
      attempt.value.sectionVersion.id, props.revision, adoptionKey.value)
    clearAttempt()
    emit('adopted')
  } catch (failure) {
    fail(failure, '采纳失败，草稿仍可查看')
    if (failure instanceof ApiRequestError && [404, 422].includes(failure.status)) clearAttempt()
    if (conflict.value) adoptionKey.value = ''
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  if (!props.canGenerate) return
  clientKey.value = sessionStorage.getItem(storageKey.value) ?? ''
  if (clientKey.value) void refreshAttempt()
})
onUnmounted(() => { disposed = true; clearTimer() })
</script>

<template>
  <article class="generation-panel">
    <div class="generation-heading">
      <div class="generation-title">
        <strong :title="section.title">{{ section.order }}. {{ section.title }}</strong>
        <small :title="section.sectionId">章节 ID：{{ section.sectionId }}</small>
      </div>
      <span
        v-if="attempt"
        class="generation-status"
        role="status"
      >{{ statusLabel[attempt.status] }}</span>
    </div>
    <p
      v-if="baselineVersionId"
      class="baseline"
      :title="baselineVersionId"
    >
      当前已采纳版本：{{ baselineVersionId }}
    </p>
    <div
      v-if="error"
      class="generation-error"
      role="alert"
    >
      {{ error }}
      <button
        v-if="conflict"
        data-action="reload-generation-conflict"
        type="button"
        @click="emit('reload')"
      >
        重新加载方案
      </button>
    </div>
    <div class="generation-actions">
      <button
        v-if="canGenerate"
        class="button button-primary"
        data-action="generate-section"
        type="button"
        :disabled="disabled || busy || active"
        @click="generate"
      >
        {{ busy ? '处理中…' : attempt?.status === 'FAILED_RETRYABLE' ? '重试生成' : '生成章节草稿' }}
      </button>
      <button
        v-if="clientKey && !active"
        class="button button-secondary"
        data-action="refresh-generation"
        type="button"
        :disabled="busy"
        @click="refreshAttempt"
      >
        刷新状态
      </button>
    </div>
    <p
      v-if="attempt?.failureCode"
      class="failure-code"
      :title="attempt.failureCode"
    >
      状态代码：{{ attempt.failureCode }}
    </p>
    <div
      v-if="attempt?.sectionVersion"
      class="draft-review"
    >
      <h4>章节草稿</h4>
      <pre class="draft-content">{{ attempt.sectionVersion.content }}</pre>
      <h4>事实声明与核验</h4>
      <ul class="claim-list">
        <li
          v-for="claim in attempt.sectionVersion.claims"
          :key="claim.claimId"
        >
          <strong>{{ claim.text }}</strong>
          <span
            v-for="check in attempt.sectionVersion.verification.verifications.filter(item => item.claimId === claim.claimId)"
            :key="check.claimId"
            class="verdict"
            :class="{ blocked: check.verdict === 'PARTIAL' || check.verdict === 'UNSUPPORTED' }"
            :title="check.reason"
          >{{ verdictLabel[check.verdict] }}</span>
          <small :title="claim.evidenceIds.join('、')">证据：{{ claim.evidenceIds.join('、') }}</small>
        </li>
      </ul>
      <p
        v-if="blocking"
        class="blocking-note"
      >
        存在未通过核验的声明，此草稿不能采纳。
      </p>
      <div
        v-else-if="canGenerate"
        class="adoption-actions"
      >
        <label><input
          v-model="reviewed"
          data-action="review-section"
          type="checkbox"
          :disabled="disabled || busy"
        >我已核对正文、引用和核验结论</label>
        <button
          class="button button-primary"
          data-action="adopt-section"
          type="button"
          :disabled="!canAdopt"
          @click="adopt"
        >
          人工采纳此草稿
        </button>
      </div>
    </div>
  </article>
</template>

<style scoped>
.generation-panel { display: grid; gap: 12px; min-width: 0; padding: 18px; border: 1px solid var(--color-border-soft); border-radius: 12px; background: #fbfcfd; }
.generation-heading { display: flex; flex-wrap: wrap; align-items: start; justify-content: space-between; gap: 10px; min-width: 0; }
.generation-title { display: grid; gap: 4px; min-width: 0; }
.generation-title strong { overflow-wrap: anywhere; }
.generation-title small, .baseline, .failure-code { margin: 0; color: var(--color-muted); font-size: 12px; overflow-wrap: anywhere; }
.generation-status, .verdict { justify-self: start; padding: 5px 9px; border-radius: 999px; color: #126456; background: #e4f3f1; font-size: 12px; font-weight: 750; }
.generation-error { padding: 10px 12px; border-radius: 8px; color: #a51f32; background: #fff1f2; overflow-wrap: anywhere; }
.generation-error button { margin-left: 8px; border: 0; color: var(--color-primary); background: transparent; text-decoration: underline; cursor: pointer; }
.generation-actions, .adoption-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.button { min-height: 42px; padding: 0 15px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); appearance: none; font: inherit; font-weight: 750; cursor: pointer; }
.button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; }
.button-primary:hover:not(:disabled) { border-color: var(--color-primary-strong); background: var(--color-primary-strong); }
.button-secondary { color: var(--color-primary); background: #fff; }
.button:disabled { opacity: .52; cursor: not-allowed; }
.draft-review { display: grid; gap: 10px; min-width: 0; border-top: 1px solid var(--color-border-soft); padding-top: 10px; }
.draft-review h4 { margin: 0; font-size: 14px; }
.draft-content { margin: 0; padding: 14px; border: 1px solid var(--color-border-soft); border-radius: 8px; background: #fff; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.7; }
.claim-list { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.claim-list li { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; min-width: 0; padding: 10px; border: 1px solid var(--color-border-soft); border-radius: 8px; background: #fff; }
.claim-list strong { min-width: 0; overflow-wrap: anywhere; }
.claim-list small { flex-basis: 100%; min-width: 0; color: var(--color-muted); overflow-wrap: anywhere; }
.verdict.blocked { color: #9f2634; background: #fff0f1; }
.blocking-note { margin: 0; color: #9f2634; font-size: 13px; }
.adoption-actions { justify-content: space-between; border-top: 1px solid var(--color-border-soft); padding-top: 10px; }
.adoption-actions label { display: flex; align-items: center; gap: 7px; min-width: 0; font-size: 13px; }
button:focus-visible, input:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
@media (max-width: 620px) { .generation-panel { padding: 14px; }.generation-actions .button, .adoption-actions .button { width: 100%; } }
</style>
