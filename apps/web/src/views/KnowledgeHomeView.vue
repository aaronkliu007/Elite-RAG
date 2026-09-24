<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import {
  knowledgeApi,
  type KnowledgeDocumentSummary,
  type KnowledgeSpaceSummary,
} from '../api/knowledge'

const spaces = ref<KnowledgeSpaceSummary[]>([])
const documents = ref<KnowledgeDocumentSummary[]>([])
const selectedSpaceId = ref('')
const query = ref('')
const loading = ref(true)
const error = ref('')

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
    error.value = failure instanceof Error ? failure.message : '加载文档失败'
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    spaces.value = await knowledgeApi.listSpaces()
    if (spaces.value[0]) await selectSpace(spaces.value[0].id)
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '加载知识空间失败'
  } finally {
    loading.value = false
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
      <RouterLink
        class="button button-primary"
        to="/upload"
      >
        上传文档
      </RouterLink>
    </header>

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
      <strong>暂无可访问的知识空间</strong><span>请联系空间管理员申请权限。</span>
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
          <span class="space-count">{{ space.documentCount }} 份</span>
          <small :title="space.description">{{ space.description }}</small>
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
            <p>{{ selectedSpace?.inReviewCount ?? 0 }} 份待审核</p>
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
.page-header p:last-child, .document-toolbar p { margin: 0; color: var(--color-muted); }
.eyebrow { margin: 0; color: var(--color-accent); font-size: 12px; font-weight: 800; letter-spacing: 0.12em; }
.button { display: inline-flex; align-items: center; justify-content: center; min-height: 44px; padding: 0 18px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); font-weight: 750; text-decoration: none; appearance: none; }
.button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; }
.button-primary:hover { border-color: var(--color-primary-strong); background: var(--color-primary-strong); }
.button-primary:focus-visible, button:focus-visible, input:focus-visible, a:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.state-panel { display: grid; justify-items: start; gap: 8px; padding: 30px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); color: var(--color-muted); background: #fff; }
.state-panel strong { color: var(--color-ink); }
.state-error { border-color: #edb8c0; background: #fff8f8; }
.state-panel button { min-height: 38px; padding: 0 14px; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: #fff; }
.workspace-grid { display: grid; grid-template-columns: minmax(220px, 280px) minmax(0, 1fr); gap: 18px; }
.space-list, .document-section { min-width: 0; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
.space-list { align-self: start; overflow: hidden; }
.space-list button { display: grid; grid-template-columns: minmax(0, 1fr) auto; width: 100%; gap: 4px 10px; padding: 16px; border: 0; border-bottom: 1px solid var(--color-border-soft); color: var(--color-ink); background: #fff; text-align: left; cursor: pointer; }
.space-list button:hover, .space-list button.selected { background: #eef7f8; }
.space-list button.selected { box-shadow: inset 4px 0 var(--color-accent); }
.space-name, .space-list small { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.space-name { font-weight: 750; }
.space-count, .space-list small { color: var(--color-muted); font-size: 12px; }
.space-list small { grid-column: 1 / -1; }
.document-section { padding: 20px; }
.document-toolbar { margin-bottom: 16px; }
.document-toolbar > div { min-width: 0; }
.document-toolbar h3 { max-width: 560px; margin: 0 0 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
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
@media (max-width: 900px) { .workspace-grid { grid-template-columns: 1fr; } .space-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); } }
@media (max-width: 640px) { .page-header, .document-toolbar { align-items: stretch; flex-direction: column; } .search-field input { width: 100%; } }
</style>
