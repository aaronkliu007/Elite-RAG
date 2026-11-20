<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi, type AdminUser } from '../api/admin'
import { ApiRequestError } from '../api/http'
import { spacesApi, type KnowledgeSpace } from '../api/spaces'
import SpaceMemberPanel from '../components/SpaceMemberPanel.vue'
import PermissionSourcesPanel from '../components/PermissionSourcesPanel.vue'

const spaces = ref<KnowledgeSpace[]>([])
const users = ref<AdminUser[] | undefined>(undefined)
const selectedSpace = ref('')
const loading = ref(true)
const error = ref('')
const grantsRevision = ref(0)

async function allPages<T>(fetchPage: (page: number) => Promise<T[]>): Promise<T[]> {
  const results: T[] = []
  for (let page = 1; ; page++) {
    const items = await fetchPage(page)
    results.push(...items)
    if (items.length < 100) return results
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [loadedSpaces, loadedUsers] = await Promise.all([
      allPages((page) => spacesApi.listSpaces(page)),
      allPages((page) => adminApi.listUsers(page)).catch((failure: unknown) => {
        if (failure instanceof ApiRequestError && failure.status === 403) return undefined
        throw failure
      }),
    ])
    spaces.value = loadedSpaces.filter((space) => space.allowedActions.includes('MANAGE_ACL'))
    users.value = loadedUsers
    selectedSpace.value = spaces.value.some((space) => space.id === selectedSpace.value) ? selectedSpace.value : spaces.value[0]?.id ?? ''
  } catch {
    error.value = '无法加载空间或用户，请检查服务后重试。'
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <section
    class="permission-admin"
    aria-label="权限管理"
  >
    <div
      v-if="loading"
      class="state"
      aria-live="polite"
    >
      正在加载空间和用户…
    </div>
    <div
      v-else-if="error"
      class="state"
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
      没有可管理权限的知识空间。
    </div>
    <template v-else>
      <div class="toolbar">
        <label>知识空间 <select
          v-model="selectedSpace"
          name="permissionSpace"
        >
          <option
            v-for="space in spaces"
            :key="space.id"
            :value="space.id"
          >{{ space.name }}</option>
        </select></label>
        <span>按空间管理成员角色</span>
      </div>
      <SpaceMemberPanel
        :key="selectedSpace"
        :space-id="selectedSpace"
        :users="users"
        :search-users="true"
        @changed="grantsRevision++"
      />
      <PermissionSourcesPanel
        :key="`${selectedSpace}:${grantsRevision}`"
        :space-id="selectedSpace"
        :spaces="spaces"
        :users="users"
      />
    </template>
  </section>
</template>

<style scoped>
.permission-admin { min-width: 0; display: grid; gap: 14px; }
.toolbar { display: flex; align-items: end; flex-wrap: wrap; gap: 16px; padding: 18px 22px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; }
.toolbar label { display: grid; gap: 6px; color: var(--color-muted); font-size: 13px; font-weight: 700; min-width: min(360px, 100%); }
.toolbar select { min-height: 42px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: #fff; color: var(--color-ink); }
.toolbar span, .scope-note { color: var(--color-muted); font-size: 13px; }
.scope-note { margin: 0; }
.state { padding: 22px; background: #fff; border: 1px solid var(--color-border); border-radius: var(--radius-panel); }
select:focus-visible, button:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
</style>
