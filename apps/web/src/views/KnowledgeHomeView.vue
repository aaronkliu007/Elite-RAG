<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { adminApi } from '../api/admin'
import { ApiRequestError } from '../api/http'
import { documentLoadError, knowledgeApi, type KnowledgeDocumentSummary } from '../api/knowledge'
import { spacesApi, type KnowledgeSpace } from '../api/spaces'
import { documentStatusLabels, parseStatusLabels } from '../documentLabels'

const spaces = ref<KnowledgeSpace[]>([])
const documents = ref<KnowledgeDocumentSummary[]>([])
const selectedSpaceId = ref('')
const query = ref('')
const loading = ref(true)
const error = ref('')
const documentsError = ref('')
const documentsLoading = ref(false)
const moreDocumentsLoading = ref(false)
const moreDocumentsError = ref('')
const documentPage = ref(1)
const hasMoreDocuments = ref(false)
const spacePage = ref(1)
const hasMoreSpaces = ref(false)
const moreSpacesLoading = ref(false)
const moreSpacesError = ref('')
const mutationError = ref('')
const creatingSpace = ref(false)
const createPanelOpen = ref(false)
const spaceNameInput = ref<HTMLInputElement | null>(null)
const canManageUsers = ref(false)
const createSpaceKey = ref('')
let createSpacePayload = ''
const createForm = reactive({ name: '', description: '' })
let documentRequest = 0
let spaceRequest = 0

const selectedSpace = computed(() => spaces.value.find((space) => space.id === selectedSpaceId.value))
const filteredDocuments = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase('zh-CN')
  if (!keyword) return documents.value
  return documents.value.filter((document) =>
    `${document.title} ${document.ownerName}`.toLocaleLowerCase('zh-CN').includes(keyword),
  )
})

async function selectSpace(spaceId: string) {
  const request = ++documentRequest
  selectedSpaceId.value = spaceId
  documents.value = []
  documentPage.value = 1
  hasMoreDocuments.value = false
  moreDocumentsLoading.value = false
  moreDocumentsError.value = ''
  documentsError.value = ''
  documentsLoading.value = true
  try {
    const result = await knowledgeApi.listDocuments(spaceId)
    if (request === documentRequest) { documents.value = result; hasMoreDocuments.value = result.length === 100 }
  } catch (failure) {
    if (request === documentRequest) documentsError.value = documentLoadError(failure, '加载文档失败')
  } finally {
    if (request === documentRequest) documentsLoading.value = false
  }
}

async function loadMoreDocuments() {
  if (!hasMoreDocuments.value || moreDocumentsLoading.value) return
  const request = documentRequest
  const spaceId = selectedSpaceId.value
  moreDocumentsLoading.value = true
  moreDocumentsError.value = ''
  try {
    const result = await knowledgeApi.listDocuments(spaceId, documentPage.value + 1)
    if (request !== documentRequest) return
    const known = new Set(documents.value.map(item => item.id))
    documents.value.push(...result.filter(item => !known.has(item.id)))
    documentPage.value++
    hasMoreDocuments.value = result.length === 100
  } catch (failure) {
    if (request !== documentRequest) return
    if (failure instanceof ApiRequestError && [401, 403, 404].includes(failure.status)) {
      documents.value = []; hasMoreDocuments.value = false; documentsError.value = documentLoadError(failure, '无法加载文档')
    } else moreDocumentsError.value = message(failure, '加载更多文档失败')
  } finally { if (request === documentRequest) moreDocumentsLoading.value = false }
}

async function loadMoreSpaces() {
  if (!hasMoreSpaces.value || moreSpacesLoading.value) return
  const request = spaceRequest
  moreSpacesLoading.value = true
  moreSpacesError.value = ''
  try {
    const result = await spacesApi.listSpaces(spacePage.value + 1)
    if (request !== spaceRequest) return
    const known = new Set(spaces.value.map(item => item.id))
    spaces.value.push(...result.filter(item => !known.has(item.id)))
    spacePage.value++
    hasMoreSpaces.value = result.length === 100
  } catch (failure) {
    if (request === spaceRequest) moreSpacesError.value = message(failure, '加载更多空间失败')
  } finally { if (request === spaceRequest) moreSpacesLoading.value = false }
}

function message(failure: unknown, fallback: string) {
  return failure instanceof ApiRequestError
    ? failure.displayMessage
    : failure instanceof Error ? failure.message : fallback
}

async function refreshSpaces() {
  spaceRequest++
  moreSpacesLoading.value = false
  moreSpacesError.value = ''
  spaces.value = await spacesApi.listSpaces()
  spacePage.value = 1
  hasMoreSpaces.value = spaces.value.length === 100
  const selectedExists = spaces.value.some((space) => space.id === selectedSpaceId.value)
  if (!selectedExists) selectedSpaceId.value = ''
  if (!selectedSpaceId.value && spaces.value[0]) await selectSpace(spaces.value[0].id)
}

async function load() {
  spaceRequest++
  loading.value = true
  error.value = ''
  try {
    const [spaceList, adminCapability] = await Promise.all([
      spacesApi.listSpaces(),
      adminApi.canManageUsers().catch(() => false),
    ])
    spaces.value = spaceList
    spacePage.value = 1
    hasMoreSpaces.value = spaceList.length === 100
    canManageUsers.value = adminCapability
    if (spaces.value[0]) await selectSpace(spaces.value[0].id)
  } catch (failure) {
    error.value = message(failure, '加载知识空间失败')
  } finally {
    loading.value = false
  }
}

async function openCreateSpace() {
  createPanelOpen.value = true
  await nextTick()
  spaceNameInput.value?.focus()
}

async function createSpace() {
  if (creatingSpace.value) return
  const payload = JSON.stringify([createForm.name, createForm.description])
  if (!createSpaceKey.value || createSpacePayload !== payload) createSpaceKey.value = crypto.randomUUID()
  createSpacePayload = payload
  creatingSpace.value = true
  mutationError.value = ''
  try {
    const created = await spacesApi.createSpace(createForm.name, createForm.description, createSpaceKey.value)
    createSpaceKey.value = ''
    createSpacePayload = ''
    createForm.name = ''
    createForm.description = ''
    createPanelOpen.value = false
    spaces.value.push(created)
    await selectSpace(created.id)
  } catch (failure) {
    const failureMessage = message(failure, '创建知识空间失败，请稍后重试')
    if (failure instanceof ApiRequestError && failure.status < 500) {
      createSpaceKey.value = ''
      createSpacePayload = ''
      if (failure.status === 409) {
        try {
          await refreshSpaces()
        } catch (refreshFailure) {
          mutationError.value = message(refreshFailure, '刷新知识空间失败，请稍后重试')
          return
        }
      }
    }
    mutationError.value = failureMessage
  } finally {
    creatingSpace.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => { spaceRequest++; documentRequest++ })
</script>

<template>
  <section class="home-view">
    <header
      v-if="canManageUsers && (loading || error || spaces.length === 0)"
      class="page-header"
    >
      <div class="header-actions">
        <button
          class="button button-primary"
          data-action="open-create-space"
          type="button"
          :aria-expanded="createPanelOpen"
          aria-controls="create-space-panel"
          @click="openCreateSpace"
        >
          ＋ 新建空间
        </button>
        <RouterLink
          class="button button-secondary"
          to="/admin/evaluations"
        >
          问答评测
        </RouterLink>
      </div>
    </header>

    <form
      v-if="canManageUsers"
      v-show="createPanelOpen"
      id="create-space-panel"
      class="create-space-card"
      data-form="create-space"
      @submit.prevent="createSpace"
    >
      <div>
        <strong>创建知识空间</strong>
        <span>为新的业务域建立独立权限边界。</span>
      </div>
      <label><span>空间名称</span><input
        ref="spaceNameInput"
        v-model.trim="createForm.name"
        name="spaceName"
        required
      ></label>
      <label><span>空间说明</span><textarea
        v-model.trim="createForm.description"
        name="spaceDescription"
        rows="2"
        required
      /></label>
      <button
        class="button button-primary"
        type="submit"
        :disabled="creatingSpace"
      >
        {{ creatingSpace ? '正在创建…' : '创建空间' }}
      </button>
      <button
        class="button button-secondary"
        type="button"
        :disabled="creatingSpace"
        @click="createPanelOpen = false"
      >
        收起
      </button>
    </form>

    <div
      v-if="mutationError"
      class="state-panel state-error"
      role="alert"
    >
      {{ mutationError }}
    </div>

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
      <strong>无法加载</strong><span>{{ error }}</span><button
        type="button"
        @click="load"
      >
        重试
      </button>
    </div>
    <div
      v-else-if="spaces.length === 0"
      class="state-panel"
    >
      <strong>暂无可访问的知识空间</strong><span>{{ canManageUsers ? '请使用“新建空间”创建首个知识空间。' : '请联系空间管理员申请权限。' }}</span>
    </div>

    <div v-else>
      <section
        class="document-section"
        aria-labelledby="document-list-title"
      >
        <div class="document-toolbar">
          <div class="space-controls">
            <h3
              id="document-list-title"
              class="sr-only"
            >
              文档列表
            </h3>
            <label class="space-select">
              <span class="sr-only">知识空间</span>
              <select
                name="knowledge-space"
                :value="selectedSpaceId"
                :title="selectedSpace?.name"
                @change="selectSpace(($event.target as HTMLSelectElement).value)"
              >
                <option
                  v-for="space in spaces"
                  :key="space.id"
                  :value="space.id"
                >{{ space.name }}</option>
              </select>
            </label>
            <RouterLink
              v-if="selectedSpace"
              class="space-settings"
              :to="`/spaces/${selectedSpace.id}`"
            >
              空间详情
            </RouterLink>
            <button
              v-if="hasMoreSpaces"
              type="button"
              class="load-spaces"
              data-action="more-spaces"
              :disabled="moreSpacesLoading"
              @click="loadMoreSpaces"
            >
              {{ moreSpacesLoading ? '正在加载…' : moreSpacesError ? '重试加载空间' : '加载更多空间' }}
            </button>
          </div>
          <div class="document-actions">
            <label class="search-field">
              <span class="sr-only">搜索文档</span>
              <input
                v-model="query"
                type="search"
                placeholder="搜索已加载的文档"
              >
            </label>
            <button
              v-if="canManageUsers"
              class="button button-secondary"
              data-action="open-create-space"
              type="button"
              :aria-expanded="createPanelOpen"
              aria-controls="create-space-panel"
              @click="openCreateSpace"
            >
              ＋ 新建空间
            </button>
            <RouterLink
              v-if="canManageUsers"
              class="button button-secondary"
              to="/admin/evaluations"
            >
              问答评测
            </RouterLink>
            <RouterLink
              class="button button-primary"
              to="/upload"
            >
              上传文档
            </RouterLink>
          </div>
        </div>
        <p
          v-if="moreSpacesError"
          role="alert"
        >
          {{ moreSpacesError }}
        </p>

        <div
          v-if="documentsLoading"
          class="empty-row"
          role="status"
        >
          正在加载文档…
        </div>
        <div
          v-else-if="documentsError"
          class="empty-row"
          role="alert"
        >
          {{ documentsError }}
          <button
            type="button"
            data-action="retry-documents"
            @click="selectSpace(selectedSpaceId)"
          >
            重试
          </button>
        </div>
        <div
          v-else-if="filteredDocuments.length === 0"
          class="empty-row"
        >
          {{ query ? '已加载的文档中没有匹配结果' : '当前空间暂无可见文档' }}
        </div>
        <div
          v-else
          class="table-wrap"
        >
          <table>
            <thead>
              <tr>
                <th class="column-title">
                  文档
                </th><th class="column-owner">
                  责任人
                </th><th class="column-status">
                  生命周期
                </th><th class="column-status">
                  解析
                </th><th class="column-date">
                  更新时间
                </th><th class="column-action">
                  操作
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="document in filteredDocuments"
                :key="document.id"
              >
                <td>
                  <span
                    class="document-title"
                    :title="document.title"
                  >{{ document.title }}</span>
                </td>
                <td data-label="责任人">
                  <span
                    class="truncate"
                    :title="document.ownerName"
                  >{{ document.ownerName }}</span>
                </td>
                <td data-label="生命周期">
                  <span class="tag">{{ documentStatusLabels[document.lifecycleStatus] ?? document.lifecycleStatus }}</span>
                </td>
                <td data-label="解析">
                  <span class="tag">{{ parseStatusLabels[document.parseStatus] ?? document.parseStatus }}</span>
                </td>
                <td data-label="更新时间">
                  {{ new Date(document.updatedAt).toLocaleDateString('zh-CN') }}
                </td>
                <td>
                  <RouterLink :to="`/documents/${document.id}`">
                    查看
                  </RouterLink>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p
          v-if="moreDocumentsError"
          role="alert"
        >
          {{ moreDocumentsError }}
        </p>
        <button
          v-if="hasMoreDocuments && !documentsLoading && !documentsError"
          type="button"
          class="button button-secondary"
          data-action="more-documents"
          :disabled="moreDocumentsLoading"
          @click="loadMoreDocuments"
        >
          {{ moreDocumentsLoading ? '正在加载…' : moreDocumentsError ? '重试加载文档' : '加载更多文档' }}
        </button>
      </section>
    </div>
  </section>
</template>

<style scoped>
.home-view { display: grid; gap: 16px; min-width: 0; }
.page-header, .document-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 24px; }
.page-header h2 { margin: 0; font-size: 24px; letter-spacing: -.03em; }
.page-header p:last-child { margin: 4px 0 0; color: var(--color-muted); font-size: 13px; }
.eyebrow { margin: 0; color: var(--color-accent); font-size: 12px; font-weight: 800; letter-spacing: 0.12em; }
.header-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 10px; }
.button { display: inline-flex; align-items: center; justify-content: center; min-height: 44px; padding: 0 18px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); font-weight: 750; text-decoration: none; appearance: none; }
.button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; }
.button-primary:hover { border-color: var(--color-primary-strong); background: var(--color-primary-strong); }
.button-secondary { color: var(--color-primary); background: #fff; }
.button-secondary:hover { background: #eef7f8; }
.button-primary:focus-visible, button:focus-visible, input:focus-visible, select:focus-visible, textarea:focus-visible, a:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.button:disabled { opacity: .55; cursor: not-allowed; }
.create-space-card { display: grid; grid-template-columns: minmax(170px, .8fr) minmax(160px, 1fr) minmax(200px, 1.5fr) auto auto; align-items: end; gap: 14px; padding: 20px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
.create-space-card > div { display: grid; align-self: center; gap: 5px; }
.create-space-card > div span, .create-space-card label > span { color: var(--color-muted); font-size: 12px; }
.create-space-card label { display: grid; gap: 7px; min-width: 0; font-weight: 700; }
.create-space-card input, .create-space-card textarea { width: 100%; min-height: 42px; padding: 9px 11px; border: 1px solid var(--color-border); border-radius: var(--radius-control); color: var(--color-ink); background: #fff; resize: vertical; }
.state-panel { display: grid; justify-items: start; gap: 8px; padding: 30px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); color: var(--color-muted); background: #fff; }
.state-panel strong { color: var(--color-ink); }
.state-error { border-color: #edb8c0; background: #fff8f8; }
.state-panel button { min-height: 38px; padding: 0 14px; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: #fff; }
.document-section { min-width: 0; padding: 20px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); container-type: inline-size; }
.document-toolbar { margin-bottom: 16px; }
.document-toolbar > div { min-width: 0; }
.space-controls { display:flex; flex-wrap:wrap; align-items:center; gap:12px; }
.document-actions { display:flex; flex-wrap:wrap; align-items:center; justify-content:flex-end; gap:10px; }
.space-select { min-width:0; }
.space-select select { width:min(320px, 38vw); min-height:42px; padding:0 34px 0 12px; border:1px solid var(--color-border); border-radius:var(--radius-control); background:#fff; color:var(--color-ink); font-weight:700; text-overflow:ellipsis; }
.load-spaces { padding:6px; border:0; background:transparent; color:var(--color-accent); cursor:pointer; }
.space-settings { color: var(--color-accent); font-size: 13px; font-weight: 750; }
.search-field input { width: min(300px, 34vw); min-height: 42px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: var(--radius-control); font: inherit; }
.table-wrap { max-width: 100%; overflow-x: auto; }
table { width: 100%; border-collapse: collapse; table-layout: fixed; }
th, td { padding: 12px 10px; overflow: hidden; border-bottom: 1px solid var(--color-border-soft); text-align: left; vertical-align: middle; }
th { color: var(--color-muted); font-size: 12px; }
.column-title { width: 36%; } .column-owner { width: 15%; } .column-status { width: 95px; } .column-date { width: 110px; } .column-action { width: 60px; }
.document-title, .document-id, .truncate { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.document-title { font-weight: 750; }
.document-id { margin-top: 4px; color: var(--color-muted); font-family: var(--font-mono); }
.tag { display: inline-block; max-width: 100%; padding: 4px 7px; overflow: hidden; border-radius: 999px; color: #26546d; background: #e6f1f7; font-size: 11px; font-weight: 750; text-overflow: ellipsis; white-space: nowrap; }
td a { color: var(--color-accent); font-weight: 750; }
.empty-row { padding: 48px 20px; color: var(--color-muted); text-align: center; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@container (max-width: 760px) {
  table, tbody { display: block; min-width: 0; }
  thead { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }
  tr { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 3px 12px; padding: 12px 0; border-bottom: 1px solid var(--color-border-soft); }
  td { min-width: 0; padding: 2px 0; border: 0; }
  td:not(:first-child):not(:last-child) { grid-column: 1; display: flex; align-items: center; gap: 8px; }
  td[data-label]::before { flex: 0 0 60px; color: var(--color-muted); font-size: 12px; content: attr(data-label); }
  td:last-child { grid-column: 2; grid-row: 1 / span 5; align-self: center; }
}
@media (max-width: 1080px) { .create-space-card { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .page-header, .document-toolbar { align-items: stretch; flex-direction: column; } .header-actions { justify-content: stretch; } .header-actions .button { flex: 1; } .create-space-card { grid-template-columns: 1fr; } .space-select { flex:1 1 100%; } .space-select select, .search-field input { width:100%; } .document-actions { justify-content:stretch; } .search-field { flex:1 1 100%; } }
</style>
