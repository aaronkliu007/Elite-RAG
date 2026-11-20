<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { ApiRequestError } from '../api/http'
import { spacesApi, type KnowledgeSpace } from '../api/spaces'
import { solutionsApi, type SolutionPhase, type SolutionSummary } from '../api/solutions'

const route = useRoute()
const router = useRouter()
const spaces = ref<KnowledgeSpace[]>([])
const selectedSpaceId = ref('')
const solutions = ref<SolutionSummary[]>([])
const loading = ref(true)
const loadingList = ref(false)
const creating = ref(false)
const error = ref('')
const mutationError = ref('')
const name = ref('')
const initialText = ref('')
const spaceDrafts = new Map<string, { name: string, initialText: string }>()
const createKey = ref('')
let createPayload = ''
let active = true
const page = ref(1)
const pageSize = 20
let listRequest = 0
let spaceLoadGeneration = 0
const spacePage = ref(1)
const hasMoreSpaces = ref(false)
const loadingMoreSpaces = ref(false)
const moreSpacesError = ref('')
const selectedSpace = computed(() => spaces.value.find((item) => item.id === selectedSpaceId.value))
const canCreate = computed(() => selectedSpace.value?.allowedActions.includes('CREATE_DOCUMENT') ?? false)
const hasNext = computed(() => solutions.value.length === pageSize)

const phaseLabel: Record<SolutionPhase, string> = {
  REQUIREMENTS_DRAFT: '需求草稿', REQUIREMENTS_CONFIRMED: '需求已确认',
  OUTLINE_DRAFT: '大纲草稿', OUTLINE_CONFIRMED: '大纲已确认',
  GENERATING: '生成中', IN_REVIEW: '评审中', READY_TO_FINALIZE: '待定稿', FINALIZED: '已定稿',
}

function message(failure: unknown, fallback: string) {
  return failure instanceof ApiRequestError ? failure.displayMessage : fallback
}

async function loadList() {
  const request = ++listRequest
  const spaceId = selectedSpaceId.value
  const requestedPage = page.value
  if (!spaceId) { solutions.value = []; loadingList.value = false; return }
  loadingList.value = true
  solutions.value = []
  error.value = ''
  try {
    const response = await solutionsApi.list(spaceId, requestedPage, pageSize)
    if (request === listRequest) solutions.value = response
  } catch (failure) {
    if (request === listRequest) error.value = message(failure, '加载方案失败，请稍后重试')
  } finally {
    if (request === listRequest) loadingList.value = false
  }
}

async function load() {
  const generation = ++spaceLoadGeneration
  const requested = String(route.params.spaceId ?? '')
  if (requested && requested !== selectedSpaceId.value) {
    listRequest++
    selectedSpaceId.value = ''
    solutions.value = []
    loadingList.value = false
  }
  loading.value = true
  error.value = ''
  try {
    let page = 1
    let batch = await spacesApi.listSpaces()
    if (!active || generation !== spaceLoadGeneration) return
    const found = [...batch]
    while (requested && !found.some(item => item.id === requested) && batch.length === 100) {
      batch = await spacesApi.listSpaces(++page)
      if (!active || generation !== spaceLoadGeneration) return
      const known = new Set(found.map(item => item.id))
      found.push(...batch.filter(item => !known.has(item.id)))
    }
    spaces.value = found
    spacePage.value = page
    hasMoreSpaces.value = batch.length === 100
    if (requested && !found.some(item => item.id === requested)) {
      selectedSpaceId.value = ''
      error.value = '空间不存在或无权访问'
      return
    }
    selectedSpaceId.value = requested || (spaces.value[0]?.id ?? '')
    if (selectedSpaceId.value && !requested) {
      await router.replace(`/spaces/${encodeURIComponent(selectedSpaceId.value)}/solutions`)
    }
    void loadList()
  } catch (failure) {
    if (active && generation === spaceLoadGeneration) error.value = message(failure, '加载知识空间失败，请稍后重试')
  } finally {
    if (active && generation === spaceLoadGeneration) loading.value = false
  }
}

async function loadMoreSpaces() {
  if (!hasMoreSpaces.value || loadingMoreSpaces.value) return
  const generation = spaceLoadGeneration
  loadingMoreSpaces.value = true; moreSpacesError.value = ''
  try {
    const batch = await spacesApi.listSpaces(spacePage.value + 1)
    if (!active || generation !== spaceLoadGeneration) return
    const known = new Set(spaces.value.map(item => item.id))
    spaces.value.push(...batch.filter(item => !known.has(item.id)))
    spacePage.value++; hasMoreSpaces.value = batch.length === 100
  } catch (failure) {
    if (active && generation === spaceLoadGeneration) moreSpacesError.value = message(failure, '加载更多空间失败')
  } finally { if (active && generation === spaceLoadGeneration) loadingMoreSpaces.value = false }
}

async function chooseSpace() {
  page.value = 1
  createKey.value = ''
  if (selectedSpaceId.value) await router.push(`/spaces/${encodeURIComponent(selectedSpaceId.value)}/solutions`)
  await loadList()
}

async function changePage(next: number) {
  page.value = next
  await loadList()
}

async function createSolution() {
  if (!canCreate.value || !selectedSpaceId.value || !name.value.trim() || !initialText.value.trim() || creating.value) return
  const spaceId = selectedSpaceId.value
  const payload = JSON.stringify([spaceId, name.value.trim(), initialText.value.trim()])
  if (!createKey.value || createPayload !== payload) createKey.value = crypto.randomUUID()
  createPayload = payload
  creating.value = true
  mutationError.value = ''
  try {
    const result = await solutionsApi.create(spaceId, name.value.trim(),
      [{ category: 'UNKNOWN', text: initialText.value.trim(), sources: [] }], 0, createKey.value)
    if (!active || selectedSpaceId.value !== spaceId) return
    createKey.value = ''
    createPayload = ''
    await router.push(`/solutions/${encodeURIComponent(result.solutionId)}`)
  } catch (failure) {
    if (!active || selectedSpaceId.value !== spaceId) return
    mutationError.value = message(failure, '创建方案失败，请稍后重试')
    if (failure instanceof ApiRequestError && failure.status < 500) { createKey.value = ''; createPayload = '' }
    if (failure instanceof ApiRequestError && failure.status === 409) {
      createKey.value = ''
      await loadList()
    }
  } finally {
    creating.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => { active = false; listRequest++; spaceLoadGeneration++ })
watch(selectedSpaceId, (next, previous) => {
  if (previous) spaceDrafts.set(previous, { name: name.value, initialText: initialText.value })
  const draft = spaceDrafts.get(next)
  name.value = draft?.name ?? ''
  initialText.value = draft?.initialText ?? ''
  mutationError.value = ''
  createKey.value = ''
  createPayload = ''
}, { flush: 'sync' })
watch(() => route.params.spaceId, (next) => {
  const target = String(next ?? '')
  if (spaces.value.some((item) => item.id === target) && target !== selectedSpaceId.value) {
    selectedSpaceId.value = target
    page.value = 1
    void loadList()
  } else if (target && !spaces.value.some((item) => item.id === target)) void load()
})
</script>

<template>
  <section class="solution-list">
    <div
      v-if="loading"
      class="state"
      aria-live="polite"
    >
      正在加载方案工作台…
    </div>
    <div
      v-else-if="error && !selectedSpaceId"
      class="state state-error"
      role="alert"
    >
      {{ error }} <button
        type="button"
        @click="load"
      >
        重试
      </button>
    </div>
    <div
      v-else-if="spaces.length === 0"
      class="state"
    >
      当前没有可访问的知识空间。
    </div>
    <template v-else>
      <section
        class="list-card"
        aria-label="方案列表"
      >
        <div class="space-toolbar">
          <label>知识空间
            <select
              v-model="selectedSpaceId"
              name="solution-space"
              :disabled="creating"
              @change="chooseSpace"
            >
              <option
                v-for="space in spaces"
                :key="space.id"
                :value="space.id"
              >{{ space.name }}</option>
            </select>
          </label>
          <button
            v-if="hasMoreSpaces"
            type="button"
            class="button"
            data-action="more-solution-spaces"
            :disabled="loadingMoreSpaces || creating"
            @click="loadMoreSpaces"
          >
            {{ loadingMoreSpaces ? '正在加载…' : moreSpacesError ? '重试加载空间' : '加载更多空间' }}
          </button>
        </div>
        <p
          v-if="moreSpacesError"
          class="state state-error"
          role="alert"
        >
          {{ moreSpacesError }}
        </p>
        <div class="list-heading">
          <h3>方案列表</h3><span>第 {{ page }} 页</span>
        </div>
        <div
          v-if="loadingList"
          class="state"
          aria-live="polite"
        >
          正在加载方案…
        </div>
        <div
          v-else-if="error"
          class="state state-error"
          role="alert"
        >
          {{ error }} <button
            type="button"
            @click="loadList"
          >
            重试
          </button>
        </div>
        <div
          v-else-if="solutions.length === 0"
          class="state"
        >
          暂无方案。可从下方创建第一份方案。
        </div>
        <ul
          v-else
          class="solution-items"
        >
          <li
            v-for="solution in solutions"
            :key="solution.id"
          >
            <RouterLink
              :to="`/solutions/${encodeURIComponent(solution.id)}`"
              class="solution-link"
            >
              <span class="solution-main"><strong
                data-solution-name
                :title="solution.name"
              >{{ solution.name }}</strong>
              </span>
              <span class="phase-tag">{{ phaseLabel[solution.phase] }}</span>
              <time :datetime="solution.updatedAt">{{ new Date(solution.updatedAt).toLocaleDateString('zh-CN') }}</time>
              <span aria-hidden="true">查看 →</span>
            </RouterLink>
          </li>
        </ul>
        <div class="pagination">
          <button
            type="button"
            :disabled="page === 1 || loadingList"
            @click="changePage(page - 1)"
          >
            上一页
          </button>
          <button
            type="button"
            :disabled="!hasNext || loadingList"
            @click="changePage(page + 1)"
          >
            下一页
          </button>
        </div>
      </section>

      <form
        v-if="canCreate"
        class="create-card"
        data-form="create-solution"
        @submit.prevent="createSolution"
      >
        <div class="form-heading">
          <strong>新建方案</strong>
        </div>
        <label>方案名称<input
          v-model="name"
          name="solution-name"
          required
          maxlength="200"
          placeholder="例如：制造业智能运维方案"
        ></label>
        <label>初始需求<textarea
          v-model="initialText"
          name="initial-requirement"
          rows="2"
          required
          placeholder="描述当前目标或待澄清事项"
        /></label>
        <button
          class="button button-primary"
          type="submit"
          :disabled="creating || !name.trim() || !initialText.trim()"
        >
          {{ creating ? '正在创建…' : '创建方案' }}
        </button>
        <p
          v-if="mutationError"
          class="form-error"
          role="alert"
        >
          {{ mutationError }}
        </p>
      </form>
    </template>
  </section>
</template>

<style scoped>
.solution-list { display: grid; gap: 14px; min-width: 0; }
.space-toolbar, .create-card, .list-card { min-width: 0; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
.page-header { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 0 0 2px; }
.header-copy { min-width: 0; } .eyebrow { margin: 0; color: var(--color-accent); font-size: 12px; font-weight: 800; }
h2 { margin: 0 0 4px; font-size: 24px; letter-spacing: -.03em; } .page-header p:last-child { margin: 0; color: var(--color-muted); font-size: 13px; }
.back-link { flex: 0 0 auto; color: var(--color-accent); font-weight: 750; }
.space-toolbar { display: flex; align-items: center; gap: 12px; padding: 0 0 14px; border: 0; border-radius: 0; box-shadow: none; }
.space-toolbar label { display: flex; align-items:center; gap: 12px; min-width: 0; color: var(--color-muted); font-size: 13px; font-weight: 700; }
.space-toolbar select { width:min(340px, 50vw); }
.space-toolbar span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--color-muted); }
.create-card { display: grid; grid-template-columns: minmax(170px, 1fr) minmax(240px, 1fr) auto; align-items: end; gap: 14px; padding: 20px; }
.form-heading { grid-column: 1 / -1; display: grid; gap: 4px; }.form-heading strong { font-size: 18px; }.form-heading span { color: var(--color-muted); font-size: 13px; }
label { display: grid; gap: 7px; min-width: 0; color: var(--color-muted); font-size: 13px; font-weight: 700; }
input, textarea, select { width: 100%; min-height: 44px; padding: 10px 12px; border: 1px solid var(--color-border); border-radius: var(--radius-control); color: var(--color-ink); background: #fff; font: inherit; }
textarea { resize: vertical; } .button, .pagination button, .state button { min-height: 44px; padding: 0 18px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); appearance: none; font: inherit; font-weight: 750; cursor: pointer; }
.button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; }
.button-primary:hover { background: var(--color-primary-strong); border-color: var(--color-primary-strong); }
.button-primary:disabled, .pagination button:disabled { opacity: .52; cursor: not-allowed; }
.form-error { grid-column: 1 / -1; margin: 0; color: #a51f32; }
.list-card { padding: 20px; }.list-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }.list-heading h3 { margin: 0 0 12px; }.list-heading span { color: var(--color-muted); }
.solution-items { margin: 0; padding: 0; list-style: none; }.solution-items li + li { border-top: 1px solid var(--color-border-soft); }
.solution-link { display: grid; grid-template-columns: minmax(0, 1fr) 112px 110px 65px; align-items: center; gap: 12px; padding: 14px 4px; color: var(--color-ink); text-decoration: none; }
.solution-link:hover { background: #f5fafb; }.solution-main { display: grid; gap: 4px; min-width: 0; }.solution-main strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.solution-main small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--color-muted); font-family: var(--font-mono); }
.phase-tag { padding: 5px 8px; border-radius: 999px; background: #e4f3f1; color: #126456; font-size: 12px; font-weight: 750; text-align: center; }.solution-link time { color: var(--color-muted); font-size: 12px; }
.pagination { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }.pagination button, .state button { color: var(--color-primary); background: #fff; }
.state { padding: 24px; color: var(--color-muted); }.state-error { color: #a51f32; background: #fff8f8; }.state button { margin-left: 10px; }
button:focus-visible, a:focus-visible, input:focus-visible, textarea:focus-visible, select:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
@media (max-width: 900px) { .create-card { grid-template-columns: 1fr 1fr; }.create-card .button { justify-self: start; }.solution-link { grid-template-columns: minmax(0, 1fr) 100px 60px; }.solution-link time { display: none; } }
@media (max-width: 620px) { .page-header, .space-toolbar { flex-direction: column; align-items: stretch; }.create-card { grid-template-columns: 1fr; }.solution-link { grid-template-columns: minmax(0, 1fr) 90px; }.solution-link > span:last-child { display: none; } }
</style>
