<script setup lang="ts">
import { computed } from 'vue'

import type { KnowledgeDocument, ParseStatus } from '../api/knowledge'

const props = defineProps<{ document: KnowledgeDocument }>()
defineEmits<{ publish: [] }>()

const canPublish = computed(
  () =>
    props.document.parseStatus === 'SUCCEEDED' &&
    props.document.metadataComplete &&
    props.document.permissions.canPublish,
)

const parseLabels: Record<ParseStatus, string> = {
  PENDING: '等待解析',
  RUNNING: '解析中',
  SUCCEEDED: '解析成功',
  FAILED: '解析失败',
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
      <div
        class="header-actions"
        aria-label="文档操作"
      >
        <button
          class="button button-primary"
          data-testid="publish-button"
          type="button"
          :disabled="!canPublish"
          @click="$emit('publish')"
        >
          发布当前版本
        </button>
      </div>
    </header>

    <div class="status-grid">
      <section
        class="status-card"
        aria-labelledby="parse-status-title"
      >
        <div class="section-heading">
          <h3 id="parse-status-title">
            解析状态
          </h3>
          <span
            class="status-tag"
            :class="`status-${document.parseStatus.toLowerCase()}`"
          >
            {{ parseLabels[document.parseStatus] }}
          </span>
        </div>
        <div
          v-if="document.parseStatus === 'PENDING' || document.parseStatus === 'RUNNING'"
          class="progress-track"
          role="progressbar"
          aria-label="文档解析进度"
          aria-valuemin="0"
          aria-valuemax="100"
          :aria-valuenow="document.parseProgress"
        >
          <span :style="{ width: `${document.parseProgress}%` }" />
        </div>
        <p
          v-if="document.parseStatus === 'FAILED'"
          class="error-summary"
          role="alert"
        >
          <strong>{{ document.parseErrors[0]?.code ?? 'CONVERSION_FAILED' }}</strong>
          {{ document.parseErrors[0]?.message ?? '解析失败，请重试或更换文件。' }}
        </p>
        <p
          v-else
          class="status-help"
        >
          {{ document.parseStatus === 'SUCCEEDED' ? '结构化内容已就绪，可进入人工审核。' : '系统正在提取正文、表格和图片。' }}
        </p>
      </section>

      <section
        class="status-card"
        aria-labelledby="metadata-title"
      >
        <div class="section-heading">
          <h3 id="metadata-title">
            必填元数据
          </h3>
          <span
            class="status-tag"
            :class="document.metadataComplete ? 'status-succeeded' : 'status-warning'"
          >
            {{ document.metadataComplete ? '已完整' : '待补充' }}
          </span>
        </div>
        <dl class="metadata-list">
          <div>
            <dt>责任人</dt>
            <dd :title="document.ownerName">
              {{ document.ownerName || '未填写' }}
            </dd>
          </div>
          <div>
            <dt>密级</dt>
            <dd>{{ document.classification || '未填写' }}</dd>
          </div>
          <div>
            <dt>权限范围</dt>
            <dd :title="document.permissionScope">
              {{ document.permissionScope || '未配置' }}
            </dd>
          </div>
        </dl>
      </section>
    </div>

    <section
      class="content-section"
      aria-labelledby="preview-title"
    >
      <div class="section-heading">
        <h3 id="preview-title">
          原文预览
        </h3>
        <span class="subtle">只读 · 当前上传版本</span>
      </div>
      <pre class="document-preview">{{ document.preview || '暂无可预览内容' }}</pre>
    </section>

    <section
      class="content-section"
      aria-labelledby="history-title"
    >
      <div class="section-heading">
        <h3 id="history-title">
          不可变版本历史
        </h3>
        <span class="subtle">共 {{ document.versions.length }} 个版本</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th class="column-version">
                版本
              </th>
              <th class="column-status">
                生命周期
              </th>
              <th class="column-status">
                解析状态
              </th>
              <th class="column-owner">
                创建人
              </th>
              <th class="column-date">
                创建时间
              </th>
              <th class="column-id">
                版本标识
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="version in document.versions"
              :key="version.id"
            >
              <td>V{{ version.number }}</td>
              <td>{{ version.status }}</td>
              <td>{{ parseLabels[version.parseStatus] }}</td>
              <td>
                <span
                  class="truncate"
                  :title="version.createdByName"
                >{{ version.createdByName }}</span>
              </td>
              <td>{{ new Date(version.createdAt).toLocaleString('zh-CN', { hour12: false }) }}</td>
              <td>
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
.review-panel {
  display: grid;
  gap: 20px;
}

.review-header,
.section-heading,
.header-actions {
  display: flex;
  align-items: center;
}

.review-header {
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}

.title-block {
  min-width: 0;
}

.eyebrow {
  margin: 0 0 6px;
  color: var(--color-accent);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

h2,
h3,
p {
  margin-top: 0;
}

h2 {
  max-width: 760px;
  margin-bottom: 6px;
  overflow-wrap: anywhere;
  font-size: clamp(24px, 3vw, 34px);
}

.document-id {
  max-width: 620px;
  margin-bottom: 0;
  overflow: hidden;
  color: var(--color-muted);
  font-family: var(--font-mono);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-actions {
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.button {
  min-height: 42px;
  padding: 0 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-control);
  font: inherit;
  font-weight: 700;
  cursor: pointer;
  appearance: none;
}

.button-primary {
  border-color: var(--color-primary);
  color: #fff;
  background: var(--color-primary);
  -webkit-text-fill-color: #fff;
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

.status-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.status-card,
.content-section {
  min-width: 0;
  padding: 20px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-panel);
  background: #fff;
  box-shadow: var(--shadow-panel);
}

.section-heading {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.section-heading h3 {
  margin-bottom: 0;
  font-size: 16px;
}

.status-tag {
  flex: 0 0 auto;
  padding: 5px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 800;
}

.status-succeeded {
  color: #17623b;
  background: #ddf5e8;
}

.status-pending,
.status-running,
.status-warning {
  color: #7a4a00;
  background: #fff1c7;
}

.status-failed {
  color: #8d2633;
  background: #ffe1e5;
}

.progress-track {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: #e7edf4;
}

.progress-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--color-accent);
}

.status-help,
.error-summary {
  margin: 12px 0 0;
  color: var(--color-muted);
  line-height: 1.6;
}

.error-summary {
  color: #8d2633;
}

.error-summary strong {
  margin-right: 8px;
}

.metadata-list {
  display: grid;
  gap: 10px;
  margin: 0;
}

.metadata-list div {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 12px;
}

.metadata-list dt {
  color: var(--color-muted);
}

.metadata-list dd {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.subtle {
  color: var(--color-muted);
  font-size: 13px;
}

.document-preview {
  max-height: 360px;
  margin: 0;
  padding: 18px;
  overflow: auto;
  border-radius: var(--radius-control);
  color: #29384c;
  background: #f5f7fa;
  font-family: inherit;
  line-height: 1.75;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.table-wrap {
  max-width: 100%;
  overflow-x: auto;
}

table {
  width: 100%;
  min-width: 840px;
  border-collapse: collapse;
  table-layout: fixed;
}

th,
td {
  padding: 12px 10px;
  overflow: hidden;
  border-bottom: 1px solid var(--color-border-soft);
  text-align: left;
  vertical-align: middle;
}

th {
  color: var(--color-muted);
  font-size: 12px;
}

.column-version {
  width: 70px;
}

.column-status {
  width: 110px;
}

.column-owner {
  width: 140px;
}

.column-date {
  width: 190px;
}

.column-id {
  width: 240px;
}

.truncate {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mono {
  font-family: var(--font-mono);
}

@media (max-width: 900px) {
  .review-header {
    flex-direction: column;
  }

  .header-actions {
    justify-content: flex-start;
  }

  .status-grid {
    grid-template-columns: 1fr;
  }
}
</style>
