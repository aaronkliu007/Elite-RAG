<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { ApiRequestError } from '../api/http'
import { spacesApi, type KnowledgeSpace } from '../api/spaces'
import { documentStatusLabels, parseStatusLabels } from '../documentLabels'
import { documentLoadError, knowledgeApi, type KnowledgeDocumentSummary } from '../api/knowledge'

const route = useRoute()
const space = ref<KnowledgeSpace | null>(null)
const loading = ref(true)
const error = ref('')
const documents = ref<KnowledgeDocumentSummary[]>([])
const documentsLoading = ref(false)
const documentsError = ref('')
let generation = 0
const spaceId = computed(() => String(route.params.spaceId ?? ''))
const canManageMembers = computed(() => space.value?.allowedActions.includes('MANAGE_ACL') ?? false)
const canCreateDocument = computed(() => space.value?.allowedActions.includes('CREATE_DOCUMENT') ?? false)
const accessLabel = computed(() => canManageMembers.value ? '可管理成员' : canCreateDocument.value ? '可编辑' : space.value?.allowedActions.includes('REVIEW_DOCUMENT') ? '可审核发布' : '只读访问')

function message(failure: unknown) {
  return failure instanceof ApiRequestError
    ? failure.displayMessage
    : failure instanceof Error ? failure.message : '加载知识空间失败，请稍后重试'
}

async function loadDocuments(id: string, request: number) {
  documentsLoading.value = true
  documentsError.value = ''
  try {
    const result = await knowledgeApi.listDocuments(id)
    if (request === generation) documents.value = result
  } catch (failure) {
    if (request === generation) documentsError.value = documentLoadError(failure, '加载文档失败，请稍后重试')
  } finally {
    if (request === generation) documentsLoading.value = false
  }
}

async function loadSpace() {
  const request = ++generation
  const id = spaceId.value
  loading.value = true
  error.value = ''
  documents.value = []
  documentsError.value = ''
  try {
    const spaces = await spacesApi.listSpaces()
    if (request !== generation) return
    space.value = spaces.find((candidate) => candidate.id === id) ?? null
    if (!space.value) error.value = '知识空间不存在或无权访问'
    else void loadDocuments(id, request)
  } catch (failure) {
    if (request !== generation) return
    space.value = null
    error.value = message(failure)
  } finally {
    if (request === generation) loading.value = false
  }
}

watch(spaceId, () => { void loadSpace() }, { immediate: true })
</script>

<template>
  <section class="space-detail">
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
      正在加载知识空间…
    </div>
    <div
      v-else-if="error"
      class="state-panel state-error"
      role="alert"
    >
      <strong>无法加载空间</strong><span>{{ error }}</span><button
        data-action="retry-space"
        type="button"
        @click="loadSpace"
      >
        重试
      </button>
    </div>
    <template v-else-if="space">
      <header class="space-header">
        <div class="space-copy">
          <h2
            data-space-name
            :title="space.name"
          >
            {{ space.name }}
          </h2>
          <p
            data-space-description
            :title="space.description"
          >
            {{ space.description || '暂无空间说明' }}
          </p>
        </div>
        <div class="space-actions">
          <span :class="['permission-badge', { manage: canManageMembers }]">
            {{ accessLabel }}
          </span>
        </div>
      </header>

      <section
        class="documents-card"
        aria-labelledby="space-documents-title"
      >
        <div class="documents-header">
          <div>
            <h3 id="space-documents-title">
              空间文档
            </h3>
          </div>
          <RouterLink
            v-if="canCreateDocument"
            class="upload-link"
            :to="{ path: '/upload', query: { spaceId: space.id } }"
          >
            ＋ 上传文档
          </RouterLink>
        </div>
        <p
          v-if="documentsLoading"
          class="documents-state"
          role="status"
        >
          正在加载文档…
        </p>
        <div
          v-else-if="documentsError"
          class="documents-state"
          role="alert"
        >
          {{ documentsError }} <button
            type="button"
            @click="loadDocuments(space.id, generation)"
          >
            重试
          </button>
        </div>
        <p
          v-else-if="documents.length === 0"
          class="documents-state"
        >
          当前空间暂无可访问文档。
        </p>
        <ul
          v-else
          class="document-list"
        >
          <li
            v-for="document in documents"
            :key="document.id"
          >
            <div>
              <RouterLink
                :to="`/documents/${encodeURIComponent(document.id)}`"
                :title="document.title"
              >
                {{ document.title }}
              </RouterLink><small>状态：{{ documentStatusLabels[document.lifecycleStatus] ?? document.lifecycleStatus }} · 解析：{{ parseStatusLabels[document.parseStatus] ?? document.parseStatus }}</small>
            </div><RouterLink
              :to="`/documents/${encodeURIComponent(document.id)}`"
              :aria-label="`查看 ${document.title}`"
            >
              查看 →
            </RouterLink>
          </li>
        </ul>
      </section>

      <section
        v-if="!canManageMembers && !canCreateDocument && !space.allowedActions.includes('REVIEW_DOCUMENT')"
        class="readonly-card"
      >
        <strong>当前为只读访问</strong>
        <p>你可以查看和检索已授权内容。</p>
      </section>
    </template>
  </section>
</template>

<style scoped>
.space-detail { display: grid; gap: 22px; min-width: 0; }
.back-link { justify-self: start; color: var(--color-accent); font-weight: 750; text-decoration: none; }
.back-link:focus-visible, button:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.space-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; min-width: 0; padding: 24px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
.space-copy { min-width: 0; }
.space-copy h2, .space-copy p:last-child { max-width: 920px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.space-copy h2 { margin: 4px 0 9px; font-size: clamp(26px, 3vw, 36px); }
.space-copy p:last-child { margin: 0; color: var(--color-muted); }
.eyebrow { margin: 0; color: var(--color-accent); font-size: 12px; font-weight: 800; letter-spacing: .12em; }
.permission-badge { flex: 0 0 auto; padding: 7px 10px; border-radius: 999px; color: #5f6876; background: #edf1f4; font-size: 12px; font-weight: 750; }
.permission-badge.manage { color: #1e6653; background: #e1f4ec; }
.space-actions { display:flex; flex-wrap:wrap; align-items:center; gap:10px; }
.member-link { color:var(--color-accent); font-weight:700; white-space:nowrap; }
.member-link:focus-visible { outline:3px solid var(--color-focus); outline-offset:3px; }
.readonly-card, .state-panel { display: grid; justify-items: start; gap: 8px; padding: 26px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); color: var(--color-muted); background: #fff; }
.readonly-card strong, .state-panel strong { color: var(--color-ink); }
.readonly-card p { margin: 0; }
.state-error { border-color: #edb8c0; background: #fff8f8; }
.state-panel button { min-height: 36px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: 8px; color: var(--color-ink); background: #fff; cursor: pointer; }
@media (max-width: 680px) { .space-header { align-items: stretch; flex-direction: column; padding: 19px; } .permission-badge { align-self: flex-start; } }
.documents-card { min-width:0; padding:22px; border:1px solid var(--color-border); border-radius:var(--radius-panel); background:#fff; box-shadow:var(--shadow-panel); }
.documents-header { display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:14px; }.documents-header h3 { margin:0 0 5px; }.documents-header p { margin:0;color:var(--color-muted);font-size:13px; }.upload-link { display:inline-flex;align-items:center;min-height:42px;padding:0 16px;border-radius:var(--radius-control);background:var(--color-primary);color:#fff;text-decoration:none;font-weight:700; }.upload-link:hover { background:var(--color-primary-strong); }.upload-link:focus-visible,.document-list a:focus-visible { outline:3px solid var(--color-focus);outline-offset:2px; }.documents-state { margin:18px 0 0;color:var(--color-muted);overflow-wrap:anywhere; }.document-list { list-style:none;padding:0;margin:16px 0 0; }.document-list li { display:flex;align-items:center;justify-content:space-between;gap:12px;padding:14px 0;border-top:1px solid var(--color-border-soft); }.document-list li>div { min-width:0; }.document-list a { color:var(--color-accent);overflow-wrap:anywhere; }.document-list small { display:block;margin-top:5px;color:var(--color-muted); }.document-list li>a { flex:none;white-space:nowrap; }
</style>
