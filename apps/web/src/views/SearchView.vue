<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'

import { ApiRequestError } from '../api/http'
import { searchApi, type SearchResponse } from '../api/search'
import { spacesApi, type KnowledgeSpace } from '../api/spaces'
import { documentsApi, type KnowledgeDocument } from '../api/documents'
import SearchResults from '../components/SearchResults.vue'

const spaces = ref<KnowledgeSpace[]>([])
const spaceId = ref('')
const queryText = ref('')
const result = ref<SearchResponse | null>(null)
const loadingSpaces = ref(true)
const searching = ref(false)
const error = ref('')
const scope = ref<'space' | 'document'>('space')
const documentId = ref('')
const documents = ref<KnowledgeDocument[]>([])
const loadingDocuments = ref(false)
const documentsError = ref('')
const hasMoreDocuments = ref(false)
const documentPage = ref(0)
let documentRequest = 0

const message = (failure: unknown, fallback: string) => failure instanceof ApiRequestError ? failure.displayMessage : fallback

async function loadSpaces() {
  try {
    spaces.value = (await spacesApi.listSpaces()).filter((space) => space.allowedActions.includes('SEARCH'))
    spaceId.value ||= spaces.value[0]?.id ?? ''
  } catch (failure) {
    error.value = message(failure, '加载知识空间失败')
  } finally {
    loadingSpaces.value = false
  }
}

async function search() {
  if (!spaceId.value || !queryText.value.trim() || searching.value
    || (scope.value === 'document' && (!documentId.value || loadingDocuments.value))) return
  searching.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await searchApi.search(spaceId.value, queryText.value.trim(), 10,
      scope.value === 'document' ? documentId.value : undefined)
  } catch (failure) {
    error.value = message(failure, '检索失败，请稍后重试')
  } finally {
    searching.value = false
  }
}

async function loadDocuments() {
  if (!spaceId.value || scope.value !== 'document' || loadingDocuments.value) return
  const request=++documentRequest
  const selectedSpace=spaceId.value
  const nextPage=documentPage.value+1
  loadingDocuments.value=true
  documentsError.value=''
  try {
    const page=await documentsApi.list(selectedSpace,nextPage,100)
    if(request!==documentRequest) return
    const known=new Set(documents.value.map(item=>item.id))
    documents.value.push(...page.filter(item=>item.spaceId===selectedSpace && !item.archived
      && item.currentPublishedVersionId && item.allowedActions.includes('VIEW') && !known.has(item.id)))
    documentPage.value=nextPage
    hasMoreDocuments.value=page.length===100
  } catch(failure) {
    if(request===documentRequest) documentsError.value=message(failure,'加载文档失败，请重试')
  } finally {
    if(request===documentRequest) loadingDocuments.value=false
  }
}

watch([spaceId,scope],()=>{
  documentRequest++
  documentId.value=''
  documents.value=[]
  documentPage.value=0
  hasMoreDocuments.value=false
  documentsError.value=''
  loadingDocuments.value=false
  result.value=null
  if(scope.value==='document') void loadDocuments()
})
watch(documentId,()=>{ result.value=null })

onMounted(loadSpaces)
</script>

<template>
  <section class="search-view">
    <header>
      <p class="eyebrow">
        组织知识检索
      </p><h2>全文检索</h2><p>结果在返回前会再次执行权限过滤。</p>
    </header>
    <form @submit.prevent="search">
      <label>知识空间<select
        v-model="spaceId"
        name="spaceId"
        :disabled="loadingSpaces || searching"
      ><option value="">请选择空间</option><option
        v-for="space in spaces"
        :key="space.id"
        :value="space.id"
      >{{ space.name }}</option></select></label>
      <label>检索范围<select
        v-model="scope"
        name="scope"
        :disabled="searching"
      ><option value="space">整个知识空间</option><option value="document">指定文档</option></select></label>
      <div
        v-if="scope === 'document'"
        class="document-field"
      >
        <label>选择文档<select
          v-model="documentId"
          name="documentId"
          :disabled="loadingDocuments || searching"
          :title="documents.find(item => item.id === documentId)?.title ?? ''"
        ><option value="">{{ loadingDocuments ? '正在加载文档…' : '请选择已发布文档' }}</option><option
          v-for="document in documents"
          :key="document.id"
          :value="document.id"
        >{{ document.title }} · {{ document.id.slice(-8) }}</option></select></label>
        <button
          v-if="hasMoreDocuments || documentsError"
          type="button"
          class="more-button"
          :disabled="loadingDocuments || searching"
          @click="loadDocuments"
        >
          {{ documentsError ? '重试加载' : '加载更多' }}
        </button>
      </div>
      <label class="query-field">检索内容<input
        v-model="queryText"
        name="queryText"
        placeholder="输入问题、关键词或方案主题"
        :disabled="searching"
      ></label>
      <button
        class="button button-primary"
        type="submit"
        :disabled="!spaceId || !queryText.trim() || searching || (scope === 'document' && (!documentId || loadingDocuments))"
      >
        {{ searching ? '正在检索…' : '检索' }}
      </button>
    </form>
    <div
      v-if="documentsError"
      class="state state-error"
      role="alert"
    >
      {{ documentsError }}
    </div>
    <div
      v-if="scope === 'document' && !loadingDocuments && !documentsError && documents.length === 0"
      class="scope-note"
      role="status"
    >
      没有可选择的已发布文档。
    </div>
    <div
      v-if="scope === 'document'"
      class="scope-note"
    >
      仅检索所选文档的已发布内容；其他文档的内容和冲突不在本次检索范围内。
    </div>
    <div
      v-if="error"
      class="state state-error"
      role="alert"
    >
      {{ error }}
    </div>
    <div
      v-if="result?.degraded"
      class="state state-warning"
      data-state="degraded"
    >
      <strong>检索已降级</strong><span>当前使用可用的召回通道，结果可能不完整。</span>
    </div>
    <div
      v-if="result && result.evidence.length === 0"
      class="state"
    >
      没有找到可访问的来源，请调整关键词后重试。
    </div>
    <SearchResults
      v-else-if="result"
      :evidence="result.evidence"
    />
  </section>
</template>

<style scoped>
.search-view { display: grid; gap: 22px; min-width: 0; } header h2 { margin: 4px 0 8px; font-size: clamp(26px, 3vw, 36px); } header p:last-child { margin: 0; color: var(--color-muted); } .eyebrow { margin: 0; color: var(--color-accent); font-size: 12px; font-weight: 800; letter-spacing: .12em; }
form { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; padding: 20px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); } label, .document-field { flex: 1 1 180px; min-width: 0; } label { display: grid; gap: 7px; color: var(--color-muted); font-size: 13px; font-weight: 700; } .document-field { display: flex; align-items: end; gap: 6px; } .document-field label { flex: 1; } .query-field { flex: 2 1 260px; } input, select { width: 100%; min-width: 0; min-height: 44px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: #fff; color: var(--color-ink); font: inherit; } .more-button { min-height: 44px; padding: 0 10px; border: 1px solid var(--color-border); border-radius: var(--radius-control); color: var(--color-ink); background: #fff; cursor: pointer; white-space: nowrap; } .scope-note { color: var(--color-muted); font-size: 13px; line-height: 1.5; }
.button { min-height: 44px; padding: 0 20px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); font-weight: 750; appearance: none; } .button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; } .button:disabled, .more-button:disabled { opacity: .52; cursor: not-allowed; } .button:focus-visible, .more-button:focus-visible, input:focus-visible, select:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.state { display: grid; gap: 5px; padding: 20px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); color: var(--color-muted); background: #fff; } .state-error { border-color: #edb8c0; color: #8d2633; background: #fff8f8; } .state-warning { border-color: #ead18c; color: #7a4a00; background: #fff9e8; }
@media (max-width: 760px) { form { align-items: stretch; flex-direction: column; } label, .document-field, .query-field { flex: auto; width: 100%; } }
</style>
