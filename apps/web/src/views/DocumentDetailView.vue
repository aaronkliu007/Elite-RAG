<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { documentsApi, type KnowledgeDocument } from '../api/documents'
import { ApiRequestError } from '../api/http'
import DocumentReviewPanel from '../components/DocumentReviewPanel.vue'

const route = useRoute()
const document = ref<KnowledgeDocument | null>(null)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const keys = new Map<string, string>()
let pollTimer: ReturnType<typeof setTimeout> | undefined
let stopped = false
let generation = 0
let loadSequence = 0
const latest = computed(() => document.value?.versions.at(-1))
const message = (failure: unknown, fallback: string) => failure instanceof ApiRequestError ? failure.displayMessage : fallback

function schedulePoll() {
  clearTimeout(pollTimer)
  if (!stopped && (latest.value?.parseStatus === 'PENDING' || latest.value?.parseStatus === 'RUNNING')) pollTimer = setTimeout(() => load(false), 2000)
}

async function load(showLoading = true) {
  const currentGeneration = generation
  const currentLoad = ++loadSequence
  const documentId = String(route.params.documentId)
  if (showLoading) loading.value = true
  try {
    const response = await documentsApi.get(documentId)
    if (currentGeneration !== generation || currentLoad !== loadSequence) return
    document.value = response; error.value = ''; schedulePoll()
  } catch (failure) {
    if (currentGeneration !== generation || currentLoad !== loadSequence) return
    if (failure instanceof ApiRequestError && [401, 403, 404].includes(failure.status)) {
      document.value = null
      clearTimeout(pollTimer)
    } else schedulePoll()
    error.value = message(failure, '文档加载失败')
  } finally {
    if (currentGeneration === generation && currentLoad === loadSequence) loading.value = false
  }
}

async function mutate(operation: string, action: (key: string) => Promise<KnowledgeDocument>, success: string) {
  if (busy.value) return
  const currentGeneration = generation
  loadSequence++
  const key = keys.get(operation) ?? crypto.randomUUID(); keys.set(operation, key); busy.value = true; error.value = ''; notice.value = ''
  try { const response = await action(key); if (currentGeneration !== generation) return; document.value = response; keys.delete(operation); notice.value = success; schedulePoll() }
  catch (failure) {
    if (currentGeneration !== generation) return
    if (failure instanceof ApiRequestError && [401, 403, 404].includes(failure.status)) { document.value = null; clearTimeout(pollTimer) } else schedulePoll()
    error.value = message(failure, '操作失败，请稍后重试'); if (failure instanceof ApiRequestError && failure.status < 500) keys.delete(operation)
  }
  finally { if (currentGeneration === generation) busy.value = false }
}

const withLatest = (callback: (documentId: string, versionId: string) => void) => { if (document.value && latest.value) callback(document.value.id, latest.value.id) }
function submitReview() { withLatest((id, version) => mutate('submit', (key) => documentsApi.submitReview(id, version, key), '已提交审核。')) }
function reject(comment: string) { withLatest((id, version) => mutate(`reject:${comment}`, (key) => documentsApi.reject(id, version, comment, key), '已驳回修改。')) }
function publish() { withLatest((id, version) => mutate('publish', (key) => documentsApi.publish(id, version, key), '当前版本已发布。')) }

watch(() => route.params.documentId, () => {
  generation++; clearTimeout(pollTimer); document.value = null; error.value = ''; notice.value = ''; busy.value = false; keys.clear()
  void load()
}, { immediate: true })
onBeforeUnmount(() => { stopped = true; generation++; clearTimeout(pollTimer) })
</script>

<template>
  <section class="detail-view">
    <RouterLink
      class="back-link"
      to="/"
    >
      ← 返回知识空间
    </RouterLink>
    <div
      v-if="loading"
      class="state"
    >
      正在加载文档…
    </div>
    <div
      v-else-if="error && !document"
      class="state error"
      role="alert"
    >
      <strong>无法查看此文档</strong><span>{{ error }}</span>
    </div>
    <template v-else-if="document">
      <p
        v-if="typeof route.query.source === 'string' && route.query.source"
        class="state"
        data-source-location-note
      >
        请在解析内容预览中核对来源；当前页面不会自动定位到检索片段。
      </p>
      <p
        v-if="notice"
        class="state success"
        role="status"
      >
        {{ notice }}
      </p>
      <p
        v-if="error"
        class="state error"
        role="alert"
      >
        {{ error }}
      </p>
      <DocumentReviewPanel
        :document="document"
        @submit-review="submitReview"
        @reject="reject"
        @publish="publish"
      />
      <p
        v-if="busy"
        class="state"
      >
        正在处理操作…
      </p>
    </template>
  </section>
</template>

<style scoped>
.detail-view { display: grid; gap: 18px; min-width: 0; } .back-link { justify-self: start; color: var(--color-accent); font-weight: 750; } .back-link:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; } .state { display: grid; gap: 7px; margin: 0; padding: 18px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; } .error { border-color: #edb8c0; color: #8d2633; background: #fff8f8; } .success { border-color: #b8dfc8; color: #17623b; background: #f1fbf5; }
</style>
