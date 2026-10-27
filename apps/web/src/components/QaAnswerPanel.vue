<script setup lang="ts">
import type { QaQuestionResponse } from '../api/qa'
import QaCitationList from './QaCitationList.vue'

defineProps<{ result: QaQuestionResponse }>()

const refusalMessages: Record<string, string> = {
  NO_AUTHORIZED_SPACE: '当前没有可用于回答的已授权知识空间，系统已严格拒答。',
  INSUFFICIENT_EVIDENCE: '依据不足，系统已严格拒答。',
  CONFLICTING_EVIDENCE: '可用依据存在冲突，系统已严格拒答。',
  UNSAFE_TO_SEND: '内容未通过安全检查，系统已严格拒答。',
  INVALID_MODEL_OUTPUT: '生成结果未通过结构校验，系统已严格拒答。',
  UNSUPPORTED_CLAIM: '回答声明缺少可验证依据，系统已严格拒答。',
}
</script>

<template>
  <article class="answer-panel">
    <div
      v-if="result.degraded"
      class="state state-warning"
      data-state="degraded"
    >
      <strong>回答已降级</strong>
      <span>部分检索能力暂不可用，回答仍只引用经过鉴权和验证的依据。</span>
    </div>
    <section
      v-if="result.status === 'ANSWERED'"
      class="answer-content"
      data-state="answered"
      aria-labelledby="qa-answer-title"
    >
      <p class="eyebrow">
        已验证回答
      </p>
      <h2 id="qa-answer-title">
        回答
      </h2>
      <p class="answer-text">
        {{ result.answer }}
      </p>
      <QaCitationList
        :request-id="result.requestId"
        :citations="result.citations"
      />
    </section>
    <section
      v-else-if="result.status === 'REFUSED'"
      class="state state-refused"
      data-state="refused"
      role="status"
    >
      <strong>本次未生成回答</strong>
      <span>{{ refusalMessages[result.refusal ?? ''] ?? '当前依据无法支持可靠回答，系统已严格拒答。' }}</span>
    </section>
    <section
      v-else
      class="state state-error"
      data-state="failed"
      role="alert"
    >
      <strong>系统处理失败</strong>
      <span>本次未生成任何回答，请稍后重新提交问题。</span>
    </section>
  </article>
</template>

<style scoped>
.answer-panel { display: grid; gap: 16px; min-width: 0; }
.answer-content { display: grid; gap: 14px; min-width: 0; padding: clamp(20px, 3vw, 30px); border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
.answer-content h2 { margin: 0; font-size: 26px; }
.eyebrow { margin: 0; color: var(--color-accent); font-size: 12px; font-weight: 850; letter-spacing: .1em; }
.answer-text { margin: 0; overflow-wrap: anywhere; color: #263a50; font-size: 17px; line-height: 1.85; white-space: pre-wrap; }
.state { display: grid; gap: 5px; min-width: 0; padding: 20px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); color: var(--color-muted); background: #fff; overflow-wrap: anywhere; }
.state-warning { border-color: #ead18c; color: #744700; background: #fff9e8; }
.state-refused { border-color: #adc7d2; color: #214b5c; background: #f3fafc; }
.state-error { border-color: #edb8c0; color: #8d2633; background: #fff8f8; }
</style>
