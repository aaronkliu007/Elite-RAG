<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiRequestError } from '../api/http'
import { qaApi, type QaQuestionResponse } from '../api/qa'
import QaAnswerPanel from '../components/QaAnswerPanel.vue'
import { takeQuestionDraft } from '../qaDraft'

const route = useRoute()
const router = useRouter()
const question = ref('')
const submitting = ref(false)
const restoring = ref(false)
const error = ref('')
const result = ref<QaQuestionResponse | null>(null)
const pendingKey = ref('')
const pendingQuestion = ref('')
let requestGeneration = 0

function failureMessage(failure: unknown) {
  return failure instanceof ApiRequestError ? failure.displayMessage : '问答服务暂时不可用，请稍后重试'
}

async function ask() {
  const normalized = question.value.trim()
  if (!normalized || submitting.value || restoring.value) return
  if (!pendingKey.value || pendingQuestion.value !== normalized) {
    pendingKey.value = crypto.randomUUID()
    pendingQuestion.value = normalized
  }
  submitting.value = true
  const generation = ++requestGeneration
  error.value = ''
  result.value = null
  try {
    const response = await qaApi.ask(normalized, pendingKey.value)
    if (generation !== requestGeneration) return
    result.value = response
    pendingKey.value = ''
    pendingQuestion.value = ''
    await router.replace({ path: '/qa', query: { requestId: result.value.requestId } })
  } catch (failure) {
    if (generation === requestGeneration) error.value = failureMessage(failure)
  } finally {
    submitting.value = false
  }
}

async function restore(requestId: string) {
  const generation = ++requestGeneration
  restoring.value = true
  error.value = ''
  result.value = null
  try {
    const response = await qaApi.get(requestId)
    if (generation === requestGeneration) result.value = response
  } catch (failure) {
    if (generation === requestGeneration) error.value = failureMessage(failure)
  } finally {
    if (generation === requestGeneration) restoring.value = false
  }
}

onMounted(() => {
  question.value = takeQuestionDraft()
})
onBeforeUnmount(() => { requestGeneration++ })
watch(() => route.query.requestId, (value) => {
  const requestId = typeof value === 'string' ? value : ''
  if (requestId && result.value?.requestId !== requestId) void restore(requestId)
  else if (!requestId) {
    requestGeneration++
    restoring.value = false
    result.value = null
    error.value = ''
  }
}, { immediate: true })
</script>

<template>
  <section class="qa-view">
    <form @submit.prevent="ask">
      <label for="qa-question">你的问题</label>
      <textarea
        id="qa-question"
        v-model="question"
        name="question"
        rows="4"
        maxlength="4000"
        placeholder="例如：公司交付评审需要准备哪些材料？"
        :disabled="submitting"
      />
      <div class="form-footer">
        <span>仅检索有权访问的已发布知识；无法验证时拒答。</span>
        <button
          class="button button-primary"
          data-action="ask"
          type="submit"
          :disabled="!question.trim() || submitting || restoring"
        >
          {{ submitting ? '正在查找依据…' : '提交问题' }}
        </button>
      </div>
    </form>

    <div
      v-if="restoring"
      class="state"
      data-state="loading"
      role="status"
      aria-live="polite"
    >
      正在恢复上次问答结果…
    </div>
    <div
      v-else-if="error"
      class="state state-error"
      role="alert"
    >
      {{ error }}
    </div>
    <QaAnswerPanel
      v-else-if="result"
      :result="result"
    />
    <div
      v-else
      class="state state-empty"
      data-state="empty"
      aria-live="polite"
    >
      <strong>等待你的问题</strong>
      <span>回答会附带关键声明和来源入口；来源打开前还会再次检查权限。</span>
    </div>
  </section>
</template>

<style scoped>
.qa-view { display: grid; gap: 22px; min-width: 0; }
.hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; min-width: 0; }
.hero > div { min-width: 0; }
.hero h1 { margin: 4px 0 8px; font-size: clamp(28px, 4vw, 42px); }
.hero p:last-child { max-width: 720px; margin: 0; color: var(--color-muted); line-height: 1.7; }
.eyebrow { margin: 0; color: var(--color-accent); font-size: 12px; font-weight: 850; letter-spacing: .12em; }
.trust-badge { flex: 0 0 auto; padding: 8px 12px; border: 1px solid #b9d8dc; border-radius: 999px; color: #185869; background: #edf8f8; font-size: 12px; font-weight: 750; }
form { display: grid; gap: 10px; min-width: 0; padding: clamp(18px, 3vw, 26px); border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
label { color: var(--color-ink); font-size: 14px; font-weight: 800; }
textarea { width: 100%; min-width: 0; resize: vertical; padding: 13px 14px; border: 1px solid var(--color-border); border-radius: var(--radius-control); color: var(--color-ink); background: #fff; font: inherit; line-height: 1.6; }
.form-footer { display: flex; align-items: center; justify-content: space-between; gap: 18px; min-width: 0; }
.form-footer span { min-width: 0; color: var(--color-muted); font-size: 12px; overflow-wrap: anywhere; }
.button { min-height: 44px; padding: 0 22px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); appearance: none; font-weight: 800; cursor: pointer; }
.button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; }
.button-primary:hover { border-color: var(--color-primary-strong); background: var(--color-primary-strong); }
.button:focus-visible, textarea:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.button:disabled { opacity: .52; cursor: not-allowed; }
.state { display: grid; gap: 5px; min-width: 0; padding: 22px; border: 1px dashed var(--color-border); border-radius: var(--radius-panel); color: var(--color-muted); background: rgb(255 255 255 / 62%); overflow-wrap: anywhere; }
.state-error { border-style: solid; border-color: #edb8c0; color: #8d2633; background: #fff8f8; }
@media (max-width: 760px) { .hero { align-items: flex-start; flex-direction: column; } .trust-badge { flex: none; } .form-footer { align-items: stretch; flex-direction: column; } .button { width: 100%; } }
</style>
