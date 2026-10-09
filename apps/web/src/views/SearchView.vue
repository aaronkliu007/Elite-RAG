<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { ApiRequestError } from '../api/http'
import { searchApi, type SearchResponse } from '../api/search'
import { spacesApi, type KnowledgeSpace } from '../api/spaces'
import SearchResults from '../components/SearchResults.vue'

const spaces = ref<KnowledgeSpace[]>([])
const spaceId = ref('')
const queryText = ref('')
const result = ref<SearchResponse | null>(null)
const loadingSpaces = ref(true)
const searching = ref(false)
const error = ref('')

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
  if (!spaceId.value || !queryText.value.trim() || searching.value) return
  searching.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await searchApi.search(spaceId.value, queryText.value.trim())
  } catch (failure) {
    error.value = message(failure, '检索失败，请稍后重试')
  } finally {
    searching.value = false
  }
}

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
        :disabled="loadingSpaces"
      ><option value="">请选择空间</option><option
        v-for="space in spaces"
        :key="space.id"
        :value="space.id"
      >{{ space.name }}</option></select></label>
      <label class="query-field">检索内容<input
        v-model="queryText"
        name="queryText"
        placeholder="输入问题、关键词或方案主题"
      ></label>
      <button
        class="button button-primary"
        type="submit"
        :disabled="!spaceId || !queryText.trim() || searching"
      >
        {{ searching ? '正在检索…' : '检索' }}
      </button>
    </form>
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
form { display: grid; grid-template-columns: minmax(180px, 260px) minmax(260px, 1fr) auto; align-items: end; gap: 12px; padding: 20px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); } label { display: grid; gap: 7px; min-width: 0; color: var(--color-muted); font-size: 13px; font-weight: 700; } input, select { width: 100%; min-height: 44px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: #fff; font: inherit; }
.button { min-height: 44px; padding: 0 20px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); font-weight: 750; appearance: none; } .button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; } .button:disabled { opacity: .52; cursor: not-allowed; } .button:focus-visible, input:focus-visible, select:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.state { display: grid; gap: 5px; padding: 20px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); color: var(--color-muted); background: #fff; } .state-error { border-color: #edb8c0; color: #8d2633; background: #fff8f8; } .state-warning { border-color: #ead18c; color: #7a4a00; background: #fff9e8; }
@media (max-width: 760px) { form { grid-template-columns: 1fr; } }
</style>
