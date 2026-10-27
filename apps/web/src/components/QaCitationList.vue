<script setup lang="ts">
import { reactive } from 'vue'

import { qaApi, type QaCitation, type QaSourceResponse } from '../api/qa'

const props = defineProps<{ requestId: string, citations: QaCitation[] }>()
const loading = reactive<Record<string, boolean>>({})
const errors = reactive<Record<string, string>>({})
const authorized = reactive<Record<string, QaSourceResponse>>({})

function locationLabel(source: QaSourceResponse) {
  const { type, coordinates } = source.location
  if (type === 'PAGE') return `PDF 第 ${coordinates.pageNumber} 页`
  if (type === 'SLIDE') return `PPT 第 ${coordinates.slideNumber} 页`
  if (type === 'TABLE') {
    return `${coordinates.tableName}：第 ${coordinates.startRow}–${coordinates.endRow} 行，第 ${coordinates.startColumn}–${coordinates.endColumn} 列`
  }
  const headings = Array.isArray(coordinates.headingPath) ? coordinates.headingPath.join(' / ') : ''
  return headings || String(coordinates.nodeId ?? 'Markdown 章节')
}

async function openSource(evidenceId: string) {
  if (loading[evidenceId]) return
  loading[evidenceId] = true
  errors[evidenceId] = ''
  try {
    const source = await qaApi.getSource(props.requestId, evidenceId)
    authorized[evidenceId] = source
    window.open(source.previewUrl, '_blank', 'noopener,noreferrer')
  } catch {
    errors[evidenceId] = '来源不存在或你已无权访问'
  } finally {
    loading[evidenceId] = false
  }
}
</script>

<template>
  <section aria-labelledby="qa-citations-title">
    <div class="citation-heading">
      <h3 id="qa-citations-title">
        回答依据
      </h3>
      <span>{{ citations.length }} 条声明</span>
    </div>
    <div
      class="citation-grid"
      data-citation-list
    >
      <article
        v-for="(citation, citationIndex) in citations"
        :key="citation.claimId"
        class="citation-card"
      >
        <p class="claim-index">
          声明 {{ citationIndex + 1 }}
        </p>
        <p
          class="claim-text"
          data-citation-claim
          :title="citation.text"
        >
          {{ citation.text }}
        </p>
        <div class="source-list">
          <div
            v-for="(evidenceId, evidenceIndex) in citation.evidenceIds"
            :key="evidenceId"
            class="source-row"
          >
            <button
              class="source-action"
              data-source-action
              type="button"
              :disabled="loading[evidenceId]"
              @click="openSource(evidenceId)"
            >
              {{ loading[evidenceId] ? '正在鉴权…' : `查看来源 ${evidenceIndex + 1}` }}
            </button>
            <span
              v-if="authorized[evidenceId]"
              class="source-meta"
            >
              <strong
                data-source-title
                :title="authorized[evidenceId]?.displayName"
              >{{ authorized[evidenceId]?.displayName }}</strong>
              <small>{{ locationLabel(authorized[evidenceId]!) }}</small>
            </span>
            <span
              v-if="errors[evidenceId]"
              class="source-error"
              role="alert"
            >{{ errors[evidenceId] }}</span>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.citation-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.citation-heading h3 { margin: 0; font-size: 18px; }
.citation-heading span { flex: 0 0 auto; color: var(--color-muted); font-size: 12px; }
.citation-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; min-width: 0; }
.citation-card { min-width: 0; padding: 18px; border: 1px solid var(--color-border); border-radius: 12px; background: #f8fafc; }
.claim-index { margin: 0 0 8px; color: var(--color-accent); font-size: 11px; font-weight: 850; letter-spacing: .08em; text-transform: uppercase; }
.claim-text { min-width: 0; margin: 0; overflow-wrap: anywhere; color: #263a50; line-height: 1.7; }
.source-list { display: grid; gap: 10px; margin-top: 14px; }
.source-row { display: flex; align-items: center; gap: 10px; min-width: 0; flex-wrap: wrap; }
.source-action { min-height: 38px; padding: 0 13px; border: 1px solid var(--color-primary); border-radius: 8px; color: var(--color-primary); background: #fff; -webkit-text-fill-color: var(--color-primary); appearance: none; font-weight: 750; cursor: pointer; }
.source-action:hover { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; }
.source-action:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.source-action:disabled { opacity: .52; cursor: not-allowed; }
.source-meta { display: grid; min-width: 0; flex: 1 1 180px; }
.source-meta strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.source-meta small { color: var(--color-muted); }
.source-error { min-width: 0; color: #8d2633; font-size: 13px; overflow-wrap: anywhere; }
@media (max-width: 760px) { .citation-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
