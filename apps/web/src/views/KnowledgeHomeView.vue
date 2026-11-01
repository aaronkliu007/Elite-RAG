<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { adminApi } from '../api/admin'
import { ApiRequestError } from '../api/http'
import { knowledgeApi, type KnowledgeDocumentSummary } from '../api/knowledge'
import { spacesApi, type KnowledgeSpace } from '../api/spaces'

const spaces = ref<KnowledgeSpace[]>([])
const documents = ref<KnowledgeDocumentSummary[]>([])
const selectedSpaceId = ref('')
const query = ref('')
const loading = ref(true)
const error = ref('')
const mutationError = ref('')
const creatingSpace = ref(false)
const canManageUsers = ref(false)
const createSpaceKey = ref('')
const createForm = reactive({ name: '', description: '' })

const selectedSpace = computed(() => spaces.value.find((space) => space.id === selectedSpaceId.value))
const filteredDocuments = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase('zh-CN')
  if (!keyword) return documents.value
  return documents.value.filter((document) =>
    `${document.title} ${document.ownerName}`.toLocaleLowerCase('zh-CN').includes(keyword),
  )
})

async function selectSpace(spaceId: string) {
  selectedSpaceId.value = spaceId
  documents.value = []
  error.value = ''
  try {
    documents.value = await knowledgeApi.listDocuments(spaceId)
  } catch (failure) {
    error.value = message(failure, '加载文档失败')
  }
}

function message(failure: unknown, fallback: string) {
  return failure instanceof ApiRequestError
    ? failure.displayMessage
    : failure instanceof Error ? failure.message : fallback
}

async function refreshSpaces() {
  spaces.value = await spacesApi.listSpaces()
  const selectedExists = spaces.value.some((space) => space.id === selectedSpaceId.value)
  if (!selectedExists) selectedSpaceId.value = ''
  if (!selectedSpaceId.value && spaces.value[0]) await selectSpace(spaces.value[0].id)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [spaceList, adminCapability] = await Promise.all([
      spacesApi.listSpaces(),
      adminApi.canManageUsers().catch(() => false),
    ])
    spaces.value = spaceList
    canManageUsers.value = adminCapability
    if (spaces.value[0]) await selectSpace(spaces.value[0].id)
  } catch (failure) {
    error.value = message(failure, '加载知识空间失败')
  } finally {
    loading.value = false
  }
}

async function createSpace() {
  if (creatingSpace.value) return
  createSpaceKey.value ||= crypto.randomUUID()
  creatingSpace.value = true
  mutationError.value = ''
  try {
    const created = await spacesApi.createSpace(createForm.name, createForm.description, createSpaceKey.value)
    createSpaceKey.value = ''
    createForm.name = ''
    createForm.description = ''
    spaces.value.push(created)
    await selectSpace(created.id)
  } catch (failure) {
    const failureMessage = message(failure, '创建知识空间失败，请稍后重试')
    if (failure instanceof ApiRequestError && failure.status < 500) {
      createSpaceKey.value = ''
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
</script>

<template>
  <section class="home-view">
    <header class="page-header">
      <div>
        <p class="eyebrow">
          组织知识资产
        </p>
        <h2>知识空间</h2>
        <p>只展示服务端确认有权访问的空间与文档。</p>
      </div>
      <div class="header-actions">
        <RouterLink
          v-if="canManageUsers"
          class="button button-secondary"
          to="/admin/evaluations"
        >
          问答评测
        </RouterLink>
        <RouterLink
          v-if="canManageUsers"
          class="button button-secondary"
          to="/admin/users"
        >
          用户管理
        </RouterLink>
        <RouterLink
          class="button button-primary"
          to="/upload"
        >
          上传文档
        </RouterLink>
      </div>
    </header>

    <form
      v-if="canManageUsers"
      class="create-space-card"
      data-form="create-space"
      @submit.prevent="createSpace"
    >
      <div>
        <strong>创建知识空间</strong>
        <span>为新的业务域建立独立权限边界。</span>
      </div>
      <label><span>空间名称</span><input
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
      <strong>暂无可访问的知识空间</strong><span>{{ canManageUsers ? '请使用上方表单创建首个知识空间。' : '请联系空间管理员申请权限。' }}</span>
    </div>

    <div
      v-else
      class="workspace-grid"
    >
      <aside
        class="space-list"
        aria-label="知识空间列表"
      >
        <button
          v-for="space in spaces"
          :key="space.id"
          type="button"
          :class="{ selected: selectedSpaceId === space.id }"
          @click="selectSpace(space.id)"
        >
          <span
            class="space-name"
            :title="space.name"
          >{{ space.name }}</span>
          <small :title="space.description">{{ space.description || '暂无说明' }}</small>
        </button>
      </aside>

      <section
        class="document-section"
        aria-labelledby="document-list-title"
      >
        <div class="document-toolbar">
          <div>
            <h3
              id="document-list-title"
              :title="selectedSpace?.name"
            >
              {{ selectedSpace?.name }}
            </h3>
            <RouterLink
              v-if="selectedSpace"
              class="space-settings"
              :to="`/spaces/${selectedSpace.id}`"
            >
              空间详情与成员
            </RouterLink>
          </div>
          <label class="search-field">
            <span class="sr-only">搜索文档</span>
            <input
              v-model="query"
              type="search"
              placeholder="搜索标题或责任人"
            >
          </label>
        </div>

        <div
          v-if="filteredDocuments.length === 0"
          class="empty-row"
        >
          {{ query ? '没有匹配的文档' : '当前空间暂无可见文档' }}
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
                  >{{ document.title }}</span><small
                    class="document-id"
                    :title="document.id"
                  >{{ document.id }}</small>
                </td>
                <td>
                  <span
                    class="truncate"
                    :title="document.ownerName"
                  >{{ document.ownerName }}</span>
                </td>
                <td><span class="tag">{{ document.lifecycleStatus }}</span></td>
                <td><span class="tag">{{ document.parseStatus }}</span></td>
                <td>{{ new Date(document.updatedAt).toLocaleDateString('zh-CN') }}</td>
                <td>
                  <RouterLink :to="`/documents/${document.id}`">
                    查看
                  </RouterLink>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.home-view { display: grid; gap: 24px; }
.page-header, .document-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 24px; }
.page-header h2 { margin: 4px 0 8px; font-size: clamp(26px, 3vw, 36px); }
.page-header p:last-child { margin: 0; color: var(--color-muted); }
.eyebrow { margin: 0; color: var(--color-accent); font-size: 12px; font-weight: 800; letter-spacing: 0.12em; }
.header-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 10px; }
.button { display: inline-flex; align-items: center; justify-content: center; min-height: 44px; padding: 0 18px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); font-weight: 750; text-decoration: none; appearance: none; }
.button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; }
.button-primary:hover { border-color: var(--color-primary-strong); background: var(--color-primary-strong); }
.button-secondary { color: var(--color-primary); background: #fff; }
.button-secondary:hover { background: #eef7f8; }
.button-primary:focus-visible, button:focus-visible, input:focus-visible, textarea:focus-visible, a:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.button:disabled { opacity: .55; cursor: not-allowed; }
.create-space-card { display: grid; grid-template-columns: minmax(190px, .8fr) minmax(190px, 1fr) minmax(260px, 1.5fr) auto; align-items: end; gap: 14px; padding: 20px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
.create-space-card > div { display: grid; align-self: center; gap: 5px; }
.create-space-card > div span, .create-space-card label > span { color: var(--color-muted); font-size: 12px; }
.create-space-card label { display: grid; gap: 7px; min-width: 0; font-weight: 700; }
.create-space-card input, .create-space-card textarea { width: 100%; min-height: 42px; padding: 9px 11px; border: 1px solid var(--color-border); border-radius: var(--radius-control); color: var(--color-ink); background: #fff; resize: vertical; }
.state-panel { display: grid; justify-items: start; gap: 8px; padding: 30px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); color: var(--color-muted); background: #fff; }
.state-panel strong { color: var(--color-ink); }
.state-error { border-color: #edb8c0; background: #fff8f8; }
.state-panel button { min-height: 38px; padding: 0 14px; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: #fff; }
.workspace-grid { display: grid; grid-template-columns: minmax(220px, 280px) minmax(0, 1fr); gap: 18px; }
.space-list, .document-section { min-width: 0; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
.space-list { align-self: start; overflow: hidden; }
.space-list button { display: grid; width: 100%; gap: 5px; padding: 16px; border: 0; border-bottom: 1px solid var(--color-border-soft); color: var(--color-ink); background: #fff; text-align: left; cursor: pointer; }
.space-list button:hover, .space-list button.selected { background: #eef7f8; }
.space-list button.selected { box-shadow: inset 4px 0 var(--color-accent); }
.space-name, .space-list small { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.space-name { font-weight: 750; }
.space-list small { color: var(--color-muted); font-size: 12px; }
.document-section { padding: 20px; }
.document-toolbar { margin-bottom: 16px; }
.document-toolbar > div { min-width: 0; }
.document-toolbar h3 { max-width: 560px; margin: 0 0 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.space-settings { color: var(--color-accent); font-size: 13px; font-weight: 750; }
.search-field input { width: min(300px, 34vw); min-height: 42px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: var(--radius-control); font: inherit; }
.table-wrap { max-width: 100%; overflow-x: auto; }
table { width: 100%; min-width: 850px; border-collapse: collapse; table-layout: fixed; }
th, td { padding: 12px 10px; overflow: hidden; border-bottom: 1px solid var(--color-border-soft); text-align: left; vertical-align: middle; }
th { color: var(--color-muted); font-size: 12px; }
.column-title { width: 32%; } .column-owner { width: 18%; } .column-status { width: 105px; } .column-date { width: 120px; } .column-action { width: 70px; }
.document-title, .document-id, .truncate { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.document-title { font-weight: 750; }
.document-id { margin-top: 4px; color: var(--color-muted); font-family: var(--font-mono); }
.tag { display: inline-block; max-width: 100%; padding: 4px 7px; overflow: hidden; border-radius: 999px; color: #26546d; background: #e6f1f7; font-size: 11px; font-weight: 750; text-overflow: ellipsis; white-space: nowrap; }
td a { color: var(--color-accent); font-weight: 750; }
.empty-row { padding: 48px 20px; color: var(--color-muted); text-align: center; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 1080px) { .create-space-card { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 900px) { .workspace-grid { grid-template-columns: 1fr; } .space-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); } }
@media (max-width: 640px) { .page-header, .document-toolbar { align-items: stretch; flex-direction: column; } .header-actions { justify-content: stretch; } .header-actions .button { flex: 1; } .create-space-card { grid-template-columns: 1fr; } .search-field input { width: 100%; } }
</style>
