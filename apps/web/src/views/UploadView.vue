<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { documentsApi } from '../api/documents'
import { ApiRequestError } from '../api/http'
import { spacesApi, type KnowledgeSpace } from '../api/spaces'
import UploadPanel, { type UploadDraft } from '../components/UploadPanel.vue'

const router = useRouter()
const spaces = ref<KnowledgeSpace[]>([])
const error = ref('')
const loading = ref(true)
const uploading = ref(false)
let uploadKey = ''

const message = (failure: unknown, fallback: string) => failure instanceof ApiRequestError ? failure.displayMessage : fallback

async function loadSpaces() {
  loading.value = true
  try { spaces.value = await spacesApi.listSpaces() } catch (failure) { error.value = message(failure, '加载知识空间失败') } finally { loading.value = false }
}

async function upload(draft: UploadDraft) {
  if (uploading.value) return
  uploadKey ||= crypto.randomUUID()
  uploading.value = true
  error.value = ''
  const form = new FormData()
  form.set('title', draft.title); form.set('classification', draft.classification)
  draft.tags.forEach((tag) => form.append('tags', tag)); form.set('file', draft.file)
  try {
    const accepted = await documentsApi.upload(draft.spaceId, form, uploadKey)
    uploadKey = ''
    await router.push(`/documents/${accepted.documentId}`)
  } catch (failure) {
    error.value = message(failure, '上传失败，请稍后重试')
    if (failure instanceof ApiRequestError && failure.status < 500) uploadKey = ''
  } finally { uploading.value = false }
}

onMounted(loadSpaces)
</script>

<template>
  <section class="upload-view">
    <RouterLink
      class="back-link"
      to="/"
    >
      ← 返回知识空间
    </RouterLink>
    <p
      v-if="error"
      class="notice error"
      role="alert"
    >
      {{ error }}
    </p>
    <p
      v-if="uploading"
      class="notice"
      role="status"
    >
      正在安全上传并创建草稿…
    </p>
    <div
      v-if="loading"
      class="notice"
    >
      正在加载可上传空间…
    </div>
    <UploadPanel
      v-else
      :spaces="spaces"
      @submit="upload"
    />
  </section>
</template>

<style scoped>
.upload-view { display: grid; justify-items: start; gap: 18px; } .back-link { color: var(--color-accent); font-weight: 750; } .back-link:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; } .notice { width: min(920px, 100%); margin: 0; padding: 14px 18px; border-radius: var(--radius-control); color: #17623b; background: #eefaf3; } .error { color: #8d2633; background: #fff1f3; }
</style>
