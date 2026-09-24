<script setup lang="ts">
import { computed, ref } from 'vue'

export interface UploadDraft {
  title: string
  ownerName: string
  classification: string
  permissionScope: string
  file: File
}

const emit = defineEmits<{ submit: [draft: UploadDraft] }>()

const title = ref('')
const ownerName = ref('')
const classification = ref('')
const permissionScope = ref('')
const file = ref<File | null>(null)

const canSubmit = computed(
  () =>
    title.value.trim().length > 0 &&
    ownerName.value.trim().length > 0 &&
    classification.value.length > 0 &&
    permissionScope.value.trim().length > 0 &&
    file.value !== null,
)

function selectFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
}

function submit() {
  if (!canSubmit.value || file.value === null) return
  emit('submit', {
    title: title.value.trim(),
    ownerName: ownerName.value.trim(),
    classification: classification.value,
    permissionScope: permissionScope.value.trim(),
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
        <span>责任人 <em>必填</em></span>
        <input
          v-model="ownerName"
          name="ownerName"
          maxlength="80"
          placeholder="团队或负责人"
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

      <label class="field field-wide">
        <span>权限范围 <em>必填</em></span>
        <input
          v-model="permissionScope"
          name="permissionScope"
          maxlength="160"
          placeholder="例如：解决方案中心及指定项目组"
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
