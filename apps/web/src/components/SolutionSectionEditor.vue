<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '../api/http'
import { solutionsApi, type SectionVersionDetail, type SectionVersionSummary } from '../api/solutions'

const props = defineProps<{
  solutionId: string, sectionId: string, revision: number,
  baselineVersionId: string | null, canEdit: boolean, disabled: boolean
}>()
const emit = defineEmits<{ adopted: [] }>()
const opened = ref(false)
const busy = ref(false)
const error = ref('')
const text = ref('')
const reviewed = ref(false)
const history = ref<SectionVersionSummary[]>([])
const candidate = ref<SectionVersionDetail | null>(null)
const serverConflict = ref('')
const leftId = ref('')
const rightId = ref('')
const left = ref<SectionVersionDetail | null>(null)
const right = ref<SectionVersionDetail | null>(null)
const canSave = computed(() => props.canEdit && !props.disabled && !busy.value
  && !!props.baselineVersionId && !!text.value.trim() && text.value.length <= 50_000
  && text.value.trim() !== (candidate.value?.content ?? left.value?.content ?? ''))
const canAdopt = computed(() => props.canEdit && !props.disabled && !busy.value
  && reviewed.value && !!candidate.value && text.value === candidate.value.content
  && !serverConflict.value)
const shownEvidence = computed(() => [...new Set((right.value?.claims ?? [])
  .flatMap(claim => claim.evidenceIds))])
const label = { MODEL_DRAFT: '模型草稿', HUMAN_EDIT: '人工候选', ADOPTED: '已采纳版本' }

async function loadCompare(side: 'left' | 'right', id: string) {
  if (!id) return
  const version = await solutionsApi.sectionVersion(props.solutionId, props.sectionId, id)
  if (side === 'left') left.value = version
  else right.value = version
}

async function open() {
  if (!props.baselineVersionId || busy.value) return
  busy.value = true
  error.value = ''
  serverConflict.value = ''
  try {
    history.value = await solutionsApi.sectionVersions(props.solutionId, props.sectionId)
    const current = await solutionsApi.sectionVersion(props.solutionId, props.sectionId,
      props.baselineVersionId)
    candidate.value = (await solutionsApi.latestSectionEdit(props.solutionId, props.sectionId,
      props.baselineVersionId)).version
    if (!history.value.some(item => item.id === current.id)) history.value.push(current)
    if (candidate.value && !history.value.some(item => item.id === candidate.value?.id))
      history.value.unshift(candidate.value)
    text.value = candidate.value?.content ?? current.content
    reviewed.value = false
    leftId.value = current.id
    rightId.value = candidate.value?.id ?? current.id
    left.value = current
    right.value = candidate.value ?? current
    opened.value = true
  } catch (failure) {
    error.value = failure instanceof ApiRequestError ? failure.displayMessage : '读取章节版本失败'
  } finally { busy.value = false }
}

async function save() {
  if (!canSave.value || !props.baselineVersionId) return
  busy.value = true
  error.value = ''
  serverConflict.value = ''
  const localText = text.value
  try {
    const saved = await solutionsApi.saveSectionEdit(props.solutionId, props.sectionId,
      props.baselineVersionId, candidate.value?.id ?? null, props.revision,
      localText, crypto.randomUUID())
    candidate.value = saved
    text.value = saved.content
    reviewed.value = false
    history.value = await solutionsApi.sectionVersions(props.solutionId, props.sectionId)
    if (!history.value.some(item => item.id === saved.id)) history.value.unshift(saved)
    if (left.value && !history.value.some(item => item.id === left.value?.id))
      history.value.push(left.value)
    rightId.value = saved.id
    right.value = saved
  } catch (failure) {
    error.value = failure instanceof ApiRequestError ? failure.displayMessage : '保存章节候选失败'
    if (failure instanceof ApiRequestError && failure.status === 409) {
      try {
        history.value = await solutionsApi.sectionVersions(props.solutionId, props.sectionId)
        const latest = (await solutionsApi.latestSectionEdit(props.solutionId, props.sectionId,
          props.baselineVersionId)).version
        const server = latest ?? await solutionsApi.sectionVersion(props.solutionId, props.sectionId,
          props.baselineVersionId)
        serverConflict.value = server.content
        candidate.value = latest
        rightId.value = server.id
        right.value = server
      } catch { serverConflict.value = '服务器版本已变化，请重新加载方案后对照。' }
    } else if (failure instanceof ApiRequestError && failure.status === 404) {
      candidate.value = null
      left.value = null
      right.value = null
      opened.value = false
    }
  } finally { busy.value = false }
}

async function adopt() {
  if (!canAdopt.value || !candidate.value) return
  busy.value = true
  error.value = ''
  try {
    await solutionsApi.adoptSection(props.solutionId, props.sectionId,
      candidate.value.id, props.revision, crypto.randomUUID())
    opened.value = false
    text.value = ''
    candidate.value = null
    emit('adopted')
  } catch (failure) {
    error.value = failure instanceof ApiRequestError ? failure.displayMessage : '采纳人工候选失败'
    if (failure instanceof ApiRequestError && failure.status === 404) {
      left.value = null
      right.value = null
      opened.value = false
    }
  } finally { busy.value = false }
}

watch(() => props.baselineVersionId, () => {
  opened.value = false
  candidate.value = null
  history.value = []
  reviewed.value = false
  serverConflict.value = ''
})
watch(text, () => { reviewed.value = false })
</script>

<template>
  <div
    v-if="baselineVersionId"
    class="section-editor"
  >
    <button
      v-if="!opened"
      class="button button-secondary"
      data-action="open-section-editor"
      type="button"
      :disabled="busy || disabled"
      @click="open"
    >
      查看版本与人工修订
    </button>
    <template v-else>
      <div class="editor-heading">
        <h4>章节人工修订</h4>
        <span>当前基线：{{ baselineVersionId }}</span>
      </div>
      <p class="editor-note">
        修订只创建候选。改写后的正文会重新标为待人工确认，采纳后仍须由有权评审者确认风险才能定稿。
      </p>
      <label
        v-if="canEdit"
        class="body-label"
      >
        修订正文
        <textarea
          v-model="text"
          data-action="section-edit-content"
          rows="9"
          maxlength="50000"
          :disabled="disabled || busy"
        />
      </label>
      <div
        v-if="error"
        class="editor-error"
        role="alert"
      >
        {{ error }}
      </div>
      <div
        v-if="serverConflict"
        class="compare-grid"
        role="status"
      >
        <div><strong>我的未保存正文</strong><pre>{{ text }}</pre></div>
        <div><strong>服务器正文</strong><pre data-action="server-conflict-content">{{ serverConflict }}</pre></div>
      </div>
      <div
        v-if="canEdit"
        class="editor-actions"
      >
        <button
          class="button button-secondary"
          data-action="save-section-edit"
          type="button"
          :disabled="!canSave"
          @click="save"
        >
          {{ busy ? '处理中…' : '保存人工候选' }}
        </button>
        <label v-if="candidate"><input
          v-model="reviewed"
          data-action="review-section-edit"
          type="checkbox"
          :disabled="busy || disabled"
        >我已核对候选正文和证据</label>
        <button
          v-if="candidate"
          class="button button-primary"
          data-action="adopt-section-edit"
          type="button"
          :disabled="!canAdopt"
          @click="adopt"
        >
          采纳人工候选
        </button>
      </div>
      <p
        v-if="candidate"
        class="editor-note"
      >
        候选 {{ candidate.id }} · 待人工确认
      </p>
      <p
        v-if="shownEvidence.length"
        class="editor-note"
      >
        引用证据：{{ shownEvidence.join('、') }}
      </p>
      <div class="compare-controls">
        <h4>版本对比</h4>
        <label>左侧版本
          <select
            v-model="leftId"
            data-action="compare-left"
            @change="loadCompare('left', leftId)"
          >
            <option
              v-for="item in history"
              :key="item.id"
              :value="item.id"
            >
              {{ label[item.kind] }} · {{ item.id }}
            </option>
          </select>
        </label>
        <label>右侧版本
          <select
            v-model="rightId"
            data-action="compare-right"
            @change="loadCompare('right', rightId)"
          >
            <option
              v-for="item in history"
              :key="item.id"
              :value="item.id"
            >
              {{ label[item.kind] }} · {{ item.id }}
            </option>
          </select>
        </label>
      </div>
      <div class="compare-grid">
        <div><strong>左侧 · {{ left?.id }}</strong><pre>{{ left?.content }}</pre></div>
        <div><strong>右侧 · {{ right?.id }}</strong><pre>{{ right?.content }}</pre></div>
      </div>
      <p
        v-if="left && right"
        class="editor-note"
        role="status"
      >
        {{ left.content === right.content ? '两个版本正文相同' : '两个版本正文存在差异，请逐段核对' }}
      </p>
    </template>
  </div>
</template>

<style scoped>
.section-editor { display: grid; gap: 12px; min-width: 0; padding: 16px; border: 1px solid var(--color-border-soft); border-radius: 12px; background: #fff; }
.editor-heading { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 8px; }
.editor-heading h4, .compare-controls h4 { margin: 0; }.editor-heading span { max-width: 100%; overflow-wrap: anywhere; color: var(--color-muted); font-size: 12px; }
.editor-note { margin: 0; color: var(--color-muted); font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.body-label, .compare-controls label { display: grid; gap: 6px; min-width: 0; color: var(--color-muted); font-size: 13px; font-weight: 700; }
textarea, select { width: 100%; min-width: 0; padding: 9px 11px; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: #fff; color: var(--color-ink); font: inherit; }
textarea { resize: vertical; line-height: 1.6; }.editor-error { padding: 10px; border-radius: 8px; color: #9d2634; background: #fff1f2; overflow-wrap: anywhere; }
.editor-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }.editor-actions label { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.button { min-height: 40px; padding: 0 14px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); appearance: none; font: inherit; font-weight: 700; cursor: pointer; }.button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; }.button-primary:hover { background: var(--color-primary-strong); }.button-secondary { color: var(--color-primary); background: #fff; }.button:disabled { opacity: .5; cursor: not-allowed; }.button:focus-visible, textarea:focus-visible, select:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.compare-controls { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }.compare-controls h4 { grid-column: 1 / -1; }
.compare-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; min-width: 0; }.compare-grid > div { min-width: 0; padding: 12px; border: 1px solid var(--color-border-soft); border-radius: 8px; background: #fafafa; }.compare-grid strong { display: block; overflow-wrap: anywhere; font-size: 12px; }.compare-grid pre { margin: 8px 0 0; overflow-wrap: anywhere; white-space: pre-wrap; font: inherit; font-size: 13px; line-height: 1.55; }
@media (max-width: 700px) { .compare-controls, .compare-grid { grid-template-columns: 1fr; } }
</style>
