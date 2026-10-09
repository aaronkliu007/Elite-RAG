<script setup lang="ts">
import { computed, ref } from 'vue'

import { documentsApi, type DocumentAction, type KnowledgeDocument, type ParseErrorCode, type ParseStatus } from '../api/documents'

const props = defineProps<{ document: KnowledgeDocument }>()
const emit = defineEmits<{ submitReview: [], reject: [comment: string], publish: [] }>()
const rejectComment = ref('')
const latest = computed(() => props.document.versions.at(-1))
const can = (action: DocumentAction) => props.document.allowedActions.includes(action)
const canSubmit = computed(() => can('SUBMIT_REVIEW') && latest.value?.status === 'DRAFT' && latest.value.parseStatus === 'SUCCEEDED')
const canPublish = computed(() => can('PUBLISH') && latest.value?.status === 'IN_REVIEW' && latest.value.parseStatus === 'SUCCEEDED')
const parseLabels: Record<ParseStatus, string> = { PENDING: '等待解析', RUNNING: '解析中', SUCCEEDED: '解析成功', FAILED: '解析失败' }
const errorLabels: Record<ParseErrorCode, string> = {
  UNSUPPORTED: '不支持的文件内容', ENCRYPTED: '文档已加密', LIMIT_EXCEEDED: '文件超出处理限制',
  CONVERSION_FAILED: '文档转换失败', TIMEOUT: '解析超时',
}

function reject() {
  if (!rejectComment.value.trim()) return
  emit('reject', rejectComment.value.trim())
}
</script>

<template>
  <article class="review-panel">
    <header class="review-header">
      <div class="title-block">
        <p class="eyebrow">
          文档审核
        </p>
        <h2 :title="document.title">
          {{ document.title }}
        </h2>
        <p
          class="document-id"
          :title="document.id"
        >
          ID：{{ document.id }}
        </p>
      </div>
      <div class="header-actions">
        <a
          v-if="can('PREVIEW')"
          class="button button-secondary"
          data-action="preview"
          :href="documentsApi.previewUrl(document.id)"
          target="_blank"
          rel="noopener"
        >预览解析内容</a>
        <a
          v-if="can('DOWNLOAD')"
          class="button button-secondary"
          data-action="download"
          :href="documentsApi.downloadUrl(document.id)"
        >下载原文件</a>
        <button
          v-if="can('SUBMIT_REVIEW')"
          class="button button-primary"
          data-action="submit-review"
          type="button"
          :disabled="!canSubmit"
          @click="$emit('submitReview')"
        >
          提交审核
        </button>
        <button
          v-if="can('PUBLISH')"
          class="button button-primary"
          data-action="publish"
          type="button"
          :disabled="!canPublish"
          @click="$emit('publish')"
        >
          发布当前版本
        </button>
      </div>
    </header>

    <section class="status-card">
      <div class="section-heading">
        <h3>解析与生命周期</h3>
        <span class="status-tag">{{ latest ? parseLabels[latest.parseStatus] : '暂无版本' }}</span>
      </div>
      <p v-if="latest">
        V{{ latest.versionNumber }} · {{ latest.status }}
      </p>
      <p
        v-if="latest?.parseStatus === 'FAILED' && latest.parseErrorCode"
        class="error-summary"
        role="alert"
      >
        {{ errorLabels[latest.parseErrorCode] }}（{{ latest.parseErrorCode }}）
      </p>
      <p v-else-if="latest?.parseStatus === 'PENDING' || latest?.parseStatus === 'RUNNING'">
        系统正在解析，页面会自动刷新。
      </p>
    </section>

    <section
      v-if="can('REJECT') && latest?.status === 'IN_REVIEW'"
      class="status-card reject-card"
    >
      <label>驳回意见<textarea
        v-model="rejectComment"
        name="rejectComment"
        rows="3"
        placeholder="请说明需要修改的内容"
      /></label>
      <button
        class="button button-danger"
        data-action="reject"
        type="button"
        :disabled="!rejectComment.trim()"
        @click="reject"
      >
        驳回修改
      </button>
    </section>

    <section class="status-card">
      <div class="section-heading">
        <h3>元数据</h3>
      </div>
      <dl><div><dt>密级</dt><dd>{{ document.metadata.classification || '未填写' }}</dd></div><div><dt>标签</dt><dd>{{ document.metadata.tags.join('、') || '暂无标签' }}</dd></div></dl>
    </section>

    <section class="status-card">
      <div class="section-heading">
        <h3>不可变版本历史</h3><span>{{ document.versions.length }} 个版本</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>版本</th><th>生命周期</th><th>解析</th><th>创建时间</th><th>版本标识</th></tr></thead><tbody>
            <tr
              v-for="version in document.versions"
              :key="version.id"
            >
              <td>V{{ version.versionNumber }}</td><td>{{ version.status }}</td><td>{{ parseLabels[version.parseStatus] }}</td><td>{{ new Date(version.createdAt).toLocaleString('zh-CN', { hour12: false }) }}</td><td>
                <span
                  class="truncate mono"
                  :title="version.id"
                >{{ version.id }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </article>
</template>

<style scoped>
.review-panel { display: grid; gap: 18px; min-width: 0; }
.review-header, .section-heading, .header-actions { display: flex; align-items: center; }
.review-header { align-items: flex-start; justify-content: space-between; gap: 24px; }
.title-block { min-width: 0; } .eyebrow { margin: 0 0 6px; color: var(--color-accent); font-size: 12px; font-weight: 800; letter-spacing: .12em; }
h2 { max-width: 760px; margin: 0 0 6px; overflow-wrap: anywhere; font-size: clamp(24px, 3vw, 34px); }
.document-id { max-width: 620px; margin: 0; overflow: hidden; color: var(--color-muted); font-family: var(--font-mono); text-overflow: ellipsis; white-space: nowrap; }
.header-actions { flex: 0 0 auto; flex-wrap: wrap; justify-content: flex-end; gap: 10px; }
.button { display: inline-flex; align-items: center; justify-content: center; min-height: 42px; padding: 0 16px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); font: inherit; font-weight: 700; text-decoration: none; appearance: none; cursor: pointer; }
.button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; } .button-secondary { color: var(--color-primary); background: #fff; } .button-danger { border-color: #b44756; color: #8d2633; background: #fff; }
.button:hover:not(:disabled) { filter: brightness(.92); } .button:focus-visible, textarea:focus-visible, a:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; } .button:disabled { opacity: .52; cursor: not-allowed; }
.status-card { min-width: 0; padding: 20px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
.section-heading { justify-content: space-between; gap: 12px; margin-bottom: 14px; } .section-heading h3 { margin: 0; font-size: 16px; } .section-heading span { color: var(--color-muted); }
.status-tag { padding: 5px 9px; border-radius: 999px; color: #26546d; background: #e6f1f7; font-size: 12px; font-weight: 800; }
.error-summary { color: #8d2633; } .reject-card { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 14px; } label { display: grid; gap: 7px; font-weight: 700; } textarea { width: 100%; padding: 10px 12px; border: 1px solid var(--color-border); border-radius: var(--radius-control); font: inherit; resize: vertical; }
dl { display: grid; gap: 10px; margin: 0; } dl div { display: grid; grid-template-columns: 72px minmax(0, 1fr); gap: 12px; } dt { color: var(--color-muted); } dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.table-wrap { max-width: 100%; overflow-x: auto; } table { width: 100%; min-width: 760px; border-collapse: collapse; table-layout: fixed; } th, td { padding: 12px 10px; overflow: hidden; border-bottom: 1px solid var(--color-border-soft); text-align: left; } th { color: var(--color-muted); font-size: 12px; } .truncate { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .mono { font-family: var(--font-mono); }
@media (max-width: 900px) { .review-header { flex-direction: column; } .header-actions { justify-content: flex-start; } .reject-card { grid-template-columns: 1fr; } }
</style>
