<script setup lang="ts">
import { computed, ref } from 'vue'

import type { KnowledgeSpace } from '../api/spaces'

export interface UploadDraft {
  spaceId: string
  title: string
  classification: string
  tags: string[]
  file: File
}

const props = defineProps<{ spaces: KnowledgeSpace[] }>()
const emit = defineEmits<{ submit: [draft: UploadDraft] }>()

const spaceId = ref('')
const title = ref('')
const classification = ref('')
const tags = ref('')
const file = ref<File | null>(null)
const allowedSpaces = computed(() => props.spaces.filter((space) => space.allowedActions.includes('CREATE_DOCUMENT')))

const canSubmit = computed(
  () =>
    spaceId.value.length > 0 && title.value.trim().length > 0 &&
    classification.value.length > 0 &&
    file.value !== null,
)

function selectFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
}

function submit() {
  if (!canSubmit.value || file.value === null) return
  emit('submit', {
    spaceId: spaceId.value,
    title: title.value.trim(),
    classification: classification.value,
    tags: tags.value.split(',').map((tag) => tag.trim()).filter(Boolean),
    file: file.value,
  })
}
</script>

<template>
  <section
    class="upload-panel"
    aria-labelledby="upload-title"
  >
    <div>
      <p class="eyebrow">
        受控入库
      </p>
      <h2 id="upload-title">
        上传新文档
      </h2>
      <p class="intro">
        原文件先进入隔离区，通过类型、大小与安全检查后才会创建不可变版本。
      </p>
    </div>

    <form
      class="upload-form"
      @submit.prevent="submit"
    >
      <p
        v-if="allowedSpaces.length === 0"
        class="permission-warning field-wide"
        role="status"
      >
        没有可上传的知识空间，请联系空间管理员授予编辑权限。
      </p>
      <label class="field field-wide">
        <span>知识空间 <em>必填</em></span>
        <select
          v-model="spaceId"
          name="spaceId"
        >
          <option value="">请选择知识空间</option>
          <option
            v-for="space in allowedSpaces"
            :key="space.id"
            :value="space.id"
          >{{ space.name }}</option>
        </select>
      </label>
      <label class="field field-wide">
        <span>文档标题 <em>必填</em></span>
        <input
          v-model="title"
          name="title"
          maxlength="160"
          placeholder="例如：制造行业智能运维解决方案"
        >
      </label>

      <label class="field">
        <span>密级 <em>必填</em></span>
        <select
          v-model="classification"
          name="classification"
        >
          <option
            value=""
            disabled
          >请选择密级</option>
          <option>内部</option>
          <option>机密</option>
          <option>公开</option>
        </select>
      </label>

      <label class="field">
        <span>标签</span>
        <input
          v-model="tags"
          name="tags"
          maxlength="160"
          placeholder="多个标签用逗号分隔"
        >
      </label>

      <label class="file-field field-wide">
        <span>选择文件 <em>必填</em></span>
        <input
          name="file"
          type="file"
          accept=".docx,.xlsx,.pdf,.pptx,.md,.png"
          @change="selectFile"
        >
        <small>支持 Word、Excel、PDF、PPT、Markdown、PNG，单文件上限 100 MB。</small>
      </label>

      <div class="form-actions field-wide">
        <p>提交后将自动开始解析，解析成功仍需人工审核才能发布。</p>
        <button
          class="button button-primary"
          data-testid="upload-submit"
          type="submit"
          :disabled="!canSubmit"
        >
          安全上传并创建草稿
        </button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.upload-panel {
  display: grid;
  width: min(920px, 100%);
  gap: 24px;
  max-width: 920px;
  padding: 28px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-panel);
  background: #fff;
  box-shadow: var(--shadow-panel);
}

.eyebrow {
  margin: 0 0 6px;
  color: var(--color-accent);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

h2 {
  margin: 0 0 10px;
  font-size: 28px;
}

.intro {
  max-width: 700px;
  margin: 0;
  color: var(--color-muted);
  line-height: 1.7;
}

.upload-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.field,
.file-field {
  display: grid;
  min-width: 0;
  gap: 8px;
}

.field-wide {
  grid-column: 1 / -1;
}

label > span {
  font-size: 14px;
  font-weight: 750;
}

em {
  color: #b72d42;
  font-size: 12px;
  font-style: normal;
}

input,
select {
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  color: var(--color-ink);
  background: #fff;
  font: inherit;
}

input:focus-visible,
select:focus-visible {
  border-color: var(--color-accent);
  outline: 3px solid var(--color-focus);
  outline-offset: 1px;
}

input[type='file'] {
  padding: 8px;
}

.file-field small,
.form-actions p {
  margin: 0;
  color: var(--color-muted);
  line-height: 1.5;
}
.permission-warning { margin: 0; padding: 12px 14px; border-radius: var(--radius-control); color: #7a4a00; background: #fff1c7; }

.form-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding-top: 6px;
}

.button {
  flex: 0 0 auto;
  min-height: 44px;
  padding: 0 18px;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-control);
  font: inherit;
  font-weight: 750;
  appearance: none;
}

.button-primary {
  color: #fff;
  background: var(--color-primary);
  -webkit-text-fill-color: #fff;
  cursor: pointer;
}

.button-primary:hover:not(:disabled) {
  border-color: var(--color-primary-strong);
  background: var(--color-primary-strong);
}

.button-primary:focus-visible {
  outline: 3px solid var(--color-focus);
  outline-offset: 2px;
}

.button-primary:disabled {
  border-color: #b7c2d0;
  color: #5e6b79;
  background: #e1e6ec;
  -webkit-text-fill-color: #5e6b79;
  cursor: not-allowed;
  opacity: 1;
}

@media (max-width: 720px) {
  .upload-panel {
    padding: 20px;
  }

  .upload-form {
    grid-template-columns: 1fr;
  }

  .field-wide {
    grid-column: auto;
  }

  .form-actions {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
