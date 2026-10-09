<script setup lang="ts">
import type { SearchEvidence, SourceLocator } from '../api/search'

defineProps<{ evidence: SearchEvidence[] }>()

function locatorLabel(locator: SourceLocator) {
  if (locator.type === 'PAGE') return `PDF 第 ${locator.pageNumber} 页`
  if (locator.type === 'SLIDE') return `PPT 第 ${locator.slideNumber} 页`
  if (locator.type === 'TABLE') return `${locator.tableName}：第 ${locator.startRow}–${locator.endRow} 行，第 ${locator.startColumn}–${locator.endColumn} 列`
  return locator.headingPath.join(' / ') || locator.nodeId
}
</script>

<template>
  <div class="results">
    <article
      v-for="item in evidence"
      :key="item.evidenceId"
      class="result-card"
    >
      <div class="result-heading">
        <h3 :title="item.title">
          {{ item.title }}
        </h3>
        <span>{{ Math.round(item.score * 100) }}%</span>
      </div>
      <p>{{ item.excerpt }}</p>
      <a
        data-source-link
        :href="`/documents/${encodeURIComponent(item.documentId)}?source=${encodeURIComponent(item.nodeId)}`"
      >{{ locatorLabel(item.locator) }} · 查看来源</a>
    </article>
  </div>
</template>

<style scoped>
.results { display: grid; gap: 14px; }
.result-card { min-width: 0; padding: 20px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
.result-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
h3 { min-width: 0; margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .result-heading span { flex: 0 0 auto; color: var(--color-muted); font-size: 12px; }
p { margin: 12px 0; overflow-wrap: anywhere; color: #33455b; line-height: 1.7; } a { color: var(--color-accent); font-weight: 750; } a:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
</style>
