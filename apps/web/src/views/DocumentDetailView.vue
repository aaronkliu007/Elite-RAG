<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
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
const latest = computed(() => document.value?.versions.at(-1))
const message = (failure: unknown, fallback: string) => failure instanceof ApiRequestError ? failure.displayMessage : fallback

function schedulePoll() {
  clearTimeout(pollTimer)
  if (!stopped && (latest.value?.parseStatus === 'PENDING' || latest.value?.parseStatus === 'RUNNING')) pollTimer = setTimeout(() => load(false), 2000)
}

async function load(showLoading = true) {
  if (showLoading) loading.value = true
  try { document.value = await documentsApi.get(String(route.params.documentId)); error.value = ''; schedulePoll() }
  catch (failure) { error.value = message(failure, '文档加载失败') }
  finally { loading.value = false }
}

async function mutate(operation: string, action: (key: string) => Promise<KnowledgeDocument>, success: string) {
  if (busy.value) return
  const key = keys.get(operation) ?? crypto.randomUUID(); keys.set(operation, key); busy.value = true; error.value = ''; notice.value = ''
  try { document.value = await action(key); keys.delete(operation); notice.value = success; schedulePoll() }
  catch (failure) { error.value = message(failure, '操作失败，请稍后重试'); if (failure instanceof ApiRequestError && failure.status < 500) keys.delete(operation) }
  finally { busy.value = false }
}

const withLatest = (callback: (documentId: string, versionId: string) => void) => { if (document.value && latest.value) callback(document.value.id, latest.value.id) }
function submitReview() { withLatest((id, version) => mutate('submit', (key) => documentsApi.submitReview(id, version, key), '已提交审核。')) }
function reject(comment: string) { withLatest((id, version) => mutate(`reject:${comment}`, (key) => documentsApi.reject(id, version, comment, key), '已驳回修改。')) }
function publish() { withLatest((id, version) => mutate('publish', (key) => documentsApi.publish(id, version, key), '当前版本已发布。')) }

onMounted(() => load())
onBeforeUnmount(() => { stopped = true; clearTimeout(pollTimer) })
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
