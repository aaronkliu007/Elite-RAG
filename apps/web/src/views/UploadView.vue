<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { documentsApi } from '../api/documents'
import { ApiRequestError } from '../api/http'
import { spacesApi, type KnowledgeSpace } from '../api/spaces'
import UploadPanel, { type UploadDraft } from '../components/UploadPanel.vue'

const router = useRouter()
const route = useRoute()
const spaces = ref<KnowledgeSpace[]>([])
const error = ref('')
const spacesError = ref('')
const loading = ref(true)
const uploading = ref(false)
const spacePage = ref(1)
const hasMoreSpaces = ref(false)
const loadingMoreSpaces = ref(false)
const moreSpacesError = ref('')
let spacesGeneration = 0
let uploadKey = ''
let previousDraft: UploadDraft | null = null

function sameDraft(left: UploadDraft, right: UploadDraft) {
  return left.spaceId === right.spaceId && left.title === right.title && left.classification === right.classification
    && left.file === right.file && left.tags.length === right.tags.length && left.tags.every((tag, index) => tag === right.tags[index])
}

const message = (failure: unknown, fallback: string) => failure instanceof ApiRequestError ? failure.displayMessage : fallback

async function loadSpaces() {
  const generation = ++spacesGeneration
  loading.value = true
  spacesError.value = ''
  try {
    const result = await spacesApi.listSpaces()
    if (generation !== spacesGeneration) return
    spaces.value = result; spacePage.value = 1; hasMoreSpaces.value = result.length === 100
  } catch (failure) { if (generation === spacesGeneration) spacesError.value = message(failure, '加载知识空间失败') }
  finally { if (generation === spacesGeneration) loading.value = false }
}

async function loadMoreSpaces() {
  if (loadingMoreSpaces.value || !hasMoreSpaces.value) return
  const generation = spacesGeneration
  loadingMoreSpaces.value = true; moreSpacesError.value = ''
  try {
    const result = await spacesApi.listSpaces(spacePage.value + 1)
    if (generation !== spacesGeneration) return
    const known = new Set(spaces.value.map(space => space.id))
    spaces.value.push(...result.filter(space => !known.has(space.id)))
    spacePage.value++; hasMoreSpaces.value = result.length === 100
  } catch (failure) { if (generation === spacesGeneration) moreSpacesError.value = message(failure, '加载更多空间失败') }
  finally { if (generation === spacesGeneration) loadingMoreSpaces.value = false }
}

async function upload(draft: UploadDraft) {
  if (uploading.value) return
  if (!uploadKey || !previousDraft || !sameDraft(previousDraft, draft)) uploadKey = crypto.randomUUID()
  previousDraft = { ...draft, tags: [...draft.tags] }
  uploading.value = true
  error.value = ''
  const form = new FormData()
  form.set('title', draft.title); form.set('classification', draft.classification)
  draft.tags.forEach((tag) => form.append('tags', tag)); form.set('file', draft.file)
  try {
    const accepted = await documentsApi.upload(draft.spaceId, form, uploadKey)
    uploadKey = ''
    previousDraft = null
    await router.push(`/documents/${accepted.documentId}`)
  } catch (failure) {
    error.value = message(failure, '上传失败，请稍后重试')
    if (failure instanceof ApiRequestError && failure.status < 500) { uploadKey = ''; previousDraft = null }
  } finally { uploading.value = false }
}

onMounted(loadSpaces)
onBeforeUnmount(() => { spacesGeneration++ })
</script>

<template>
  <section class="upload-view">
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
    <div
      v-else-if="spacesError"
      class="notice error"
      role="alert"
    >
      {{ spacesError }}
      <button
        type="button"
        data-action="retry-spaces"
        @click="loadSpaces"
      >
        重新加载空间
      </button>
    </div>
    <UploadPanel
      v-else
      :spaces="spaces"
      :more-spaces-available="hasMoreSpaces"
      :initial-space-id="typeof route.query.spaceId === 'string' ? route.query.spaceId : undefined"
      @submit="upload"
    />
    <p
      v-if="moreSpacesError"
      class="notice error"
      role="alert"
    >
      {{ moreSpacesError }}
    </p>
    <button
      v-if="hasMoreSpaces && !loading && !spacesError"
      type="button"
      data-action="more-upload-spaces"
      :disabled="loadingMoreSpaces || uploading"
      @click="loadMoreSpaces"
    >
      {{ loadingMoreSpaces ? '正在加载…' : moreSpacesError ? '重试加载更多空间' : '加载更多空间' }}
    </button>
  </section>
</template>

<style scoped>
.upload-view { display: grid; gap: 14px; min-width: 0; } .notice { width: 100%; margin: 0; padding: 14px 18px; border-radius: var(--radius-control); color: #17623b; background: #eefaf3; } .error { color: #8d2633; background: #fff1f3; }
</style>
