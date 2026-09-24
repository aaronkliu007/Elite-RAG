<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { knowledgeApi, type KnowledgeDocument } from '../api/knowledge'
import AclScopePanel from '../components/AclScopePanel.vue'
import DocumentReviewPanel from '../components/DocumentReviewPanel.vue'

const route = useRoute()
const document = ref<KnowledgeDocument | null>(null)
const loading = ref(true)
const publishing = ref(false)
const error = ref('')
const notice = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    document.value = await knowledgeApi.getDocument(String(route.params.documentId))
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '文档加载失败'
  } finally {
    loading.value = false
  }
}

async function publish() {
  const current = document.value
  const version = current?.versions.at(-1)
  if (!current || !version || publishing.value) return
  publishing.value = true
  notice.value = ''
  error.value = ''
  try {
    document.value = await knowledgeApi.publish(current.id, version.id)
    notice.value = '当前版本已发布。'
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '发布失败'
  } finally {
    publishing.value = false
  }
}

onMounted(load)
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
      class="state-panel"
      aria-live="polite"
    >
      正在加载文档…
    </div>
    <div
      v-else-if="error && !document"
      class="state-panel state-error"
      role="alert"
    >
      <strong>无法查看此文档</strong>
      <span>{{ error }}</span>
      <RouterLink to="/">
        返回可访问的知识空间
      </RouterLink>
    </div>
    <template v-else-if="document">
      <p
        v-if="notice"
        class="notice"
        role="status"
      >
        {{ notice }}
      </p>
      <p
        v-if="error"
        class="notice notice-error"
        role="alert"
      >
        {{ error }}
      </p>
      <DocumentReviewPanel
        :document="document"
        @publish="publish"
      />
      <AclScopePanel
        :permission-scope="document.permissionScope"
      />
      <p
        v-if="publishing"
        class="publishing"
        aria-live="polite"
      >
        正在发布当前版本…
      </p>
    </template>
  </section>
</template>

<style scoped>
.detail-view { display: grid; gap: 18px; }
.back-link { justify-self: start; color: var(--color-accent); font-weight: 750; text-decoration: none; }
.back-link:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.state-panel, .notice { display: grid; justify-items: start; gap: 8px; padding: 22px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; }
.state-error, .notice-error { border-color: #edb8c0; color: #8d2633; background: #fff8f8; }
.notice { margin: 0; border-color: #b8dfc8; color: #17623b; background: #f1fbf5; }
.publishing { margin: 0; color: var(--color-muted); }
</style>
