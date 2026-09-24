<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { knowledgeApi } from '../api/knowledge'
import UploadPanel, { type UploadDraft } from '../components/UploadPanel.vue'

const router = useRouter()
const error = ref('')
const uploading = ref(false)

async function upload(draft: UploadDraft) {
  if (uploading.value) return
  uploading.value = true
  error.value = ''
  const form = new FormData()
  form.set('title', draft.title)
  form.set('ownerName', draft.ownerName)
  form.set('classification', draft.classification)
  form.set('permissionScope', draft.permissionScope)
  form.set('file', draft.file)
  try {
    const document = await knowledgeApi.upload('default', form)
    await router.push(`/documents/${document.id}`)
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '上传失败'
  } finally {
    uploading.value = false
  }
}
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
      class="error"
      role="alert"
    >
      {{ error }}
    </p>
    <p
      v-if="uploading"
      class="status"
      role="status"
    >
      正在安全上传并创建草稿…
    </p>
    <UploadPanel @submit="upload" />
  </section>
</template>

<style scoped>
.upload-view { display: grid; justify-items: start; gap: 18px; }
.back-link { color: var(--color-accent); font-weight: 750; text-decoration: none; }
.back-link:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.error, .status { width: min(920px, 100%); margin: 0; padding: 14px 18px; border-radius: var(--radius-control); color: #8d2633; background: #fff1f3; }
.status { color: #17623b; background: #eefaf3; }
</style>
