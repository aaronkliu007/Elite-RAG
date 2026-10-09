<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { ApiRequestError } from '../api/http'
import { spacesApi, type KnowledgeSpace } from '../api/spaces'
import SpaceMemberPanel from '../components/SpaceMemberPanel.vue'

const route = useRoute()
const space = ref<KnowledgeSpace | null>(null)
const loading = ref(true)
const error = ref('')
const spaceId = computed(() => String(route.params.spaceId ?? ''))
const canManageMembers = computed(() => space.value?.allowedActions.includes('MANAGE_ACL') ?? false)

function message(failure: unknown) {
  return failure instanceof ApiRequestError
    ? failure.displayMessage
    : failure instanceof Error ? failure.message : '加载知识空间失败，请稍后重试'
}

async function loadSpace() {
  loading.value = true
  error.value = ''
  try {
    const spaces = await spacesApi.listSpaces()
    space.value = spaces.find((candidate) => candidate.id === spaceId.value) ?? null
    if (!space.value) error.value = '知识空间不存在或无权访问'
  } catch (failure) {
    space.value = null
    error.value = message(failure)
  } finally {
    loading.value = false
  }
}

onMounted(loadSpace)
</script>

<template>
  <section class="space-detail">
    <RouterLink
      class="back-link"
      to="/"
    >
      ← 返回知识空间
    </RouterLink>

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
      <strong>无法加载空间</strong><span>{{ error }}</span><button
        data-action="retry-space"
        type="button"
        @click="loadSpace"
      >
        重试
      </button>
    </div>
    <template v-else-if="space">
      <header class="space-header">
        <div class="space-copy">
          <p class="eyebrow">
            知识空间详情
          </p>
          <h2
            data-space-name
            :title="space.name"
          >
            {{ space.name }}
          </h2>
          <p
            data-space-description
            :title="space.description"
          >
            {{ space.description || '暂无空间说明' }}
          </p>
        </div>
        <span :class="['permission-badge', { manage: canManageMembers }]">
          {{ canManageMembers ? '可管理成员' : '只读访问' }}
        </span>
      </header>

      <SpaceMemberPanel
        v-if="canManageMembers"
        :space-id="space.id"
      />
      <section
        v-else
        class="readonly-card"
      >
        <strong>当前为只读访问</strong>
        <p>你可以查看和检索已授权内容，成员与角色配置仅对空间管理员开放。</p>
      </section>
    </template>
  </section>
</template>

<style scoped>
.space-detail { display: grid; gap: 22px; min-width: 0; }
.back-link { justify-self: start; color: var(--color-accent); font-weight: 750; text-decoration: none; }
.back-link:focus-visible, button:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.space-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; min-width: 0; padding: 24px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
.space-copy { min-width: 0; }
.space-copy h2, .space-copy p:last-child { max-width: 920px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.space-copy h2 { margin: 4px 0 9px; font-size: clamp(26px, 3vw, 36px); }
.space-copy p:last-child { margin: 0; color: var(--color-muted); }
.eyebrow { margin: 0; color: var(--color-accent); font-size: 12px; font-weight: 800; letter-spacing: .12em; }
.permission-badge { flex: 0 0 auto; padding: 7px 10px; border-radius: 999px; color: #5f6876; background: #edf1f4; font-size: 12px; font-weight: 750; }
.permission-badge.manage { color: #1e6653; background: #e1f4ec; }
.readonly-card, .state-panel { display: grid; justify-items: start; gap: 8px; padding: 26px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); color: var(--color-muted); background: #fff; }
.readonly-card strong, .state-panel strong { color: var(--color-ink); }
.readonly-card p { margin: 0; }
.state-error { border-color: #edb8c0; background: #fff8f8; }
.state-panel button { min-height: 36px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: 8px; color: var(--color-ink); background: #fff; cursor: pointer; }
@media (max-width: 680px) { .space-header { align-items: stretch; flex-direction: column; padding: 19px; } .permission-badge { align-self: flex-start; } }
</style>
