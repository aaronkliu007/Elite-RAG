<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { spacesApi, type PermissionGrant } from '../api/spaces'
import type { AdminUser } from '../api/admin'
import EffectiveUserPermissionsPanel from './EffectiveUserPermissionsPanel.vue'
const props = defineProps<{ spaceId: string, spaces?: Array<{ id: string, name: string }>, users?: AdminUser[] }>()
const grants = ref<PermissionGrant[]>([])
const overviewMode = ref<'space' | 'user'>('space')
let generation = 0
const query = ref('')
const error = ref('')
const loading = ref(true)
const page = ref(1)
const hasMore = ref(false)
const labels: Record<string, string> = { DIRECT: '直接授权', SPACE_ROLE: '空间角色', LEGACY_UNKNOWN: '历史来源待确认' }
const filtered = computed(() => {
  const value = query.value.trim().toLocaleLowerCase('zh-CN')
  return grants.value.filter(item => !value || `${item.displayName ?? ''} ${item.username ?? ''} ${item.principalId}`.toLocaleLowerCase('zh-CN').includes(value))
})
async function load(more = false) {
  const current = ++generation
  loading.value = true; error.value = ''
  try {
    const target = more ? page.value + 1 : 1
    const items = await spacesApi.listPermissionGrants(props.spaceId, target)
    if (current !== generation) return
    grants.value = more ? [...grants.value, ...items] : items
    page.value = target; hasMore.value = items.length === 100
  } catch { if (current === generation) { grants.value = []; error.value = '无法完整加载授权来源，请重试。' } }
  finally { if (current === generation) loading.value = false }
}
onMounted(() => load())
watch(overviewMode, (value) => { if (value === 'space') { grants.value = []; void load() } })
</script>
<template>
  <section
    class="sources"
    aria-label="空间授权来源"
  >
    <div class="toolbar">
      <strong>授权来源</strong><label>查看方式<select
        v-model="overviewMode"
        name="overviewMode"
      ><option value="space">按空间</option><option value="user">按用户</option></select></label><label v-if="overviewMode === 'space'"><span class="sr-only">筛选已加载的授权</span><input
        v-model="query"
        name="permissionQuery"
        placeholder="筛选姓名或账号"
      ></label>
    </div>
    <EffectiveUserPermissionsPanel
      v-if="overviewMode === 'user'"
      :space-id="spaceId"
      :spaces="spaces ?? []"
      :users="users"
    />
    <template v-else>
      <p
        v-if="error"
        role="alert"
      >
        {{ error }} <button
          type="button"
          @click="load()"
        >
          重试
        </button>
      </p>
      <p v-else-if="loading && grants.length === 0">
        正在加载授权…
      </p>
      <p v-else-if="filtered.length === 0">
        暂无匹配的授权记录。
      </p>
      <div
        v-else
        class="table-wrap"
      >
        <table>
          <thead>
            <tr>
              <th>授权对象</th><th>来源</th><th>有效期</th>
            </tr>
          </thead><tbody>
            <tr
              v-for="grant in filtered"
              :key="grant.id"
            >
              <td>{{ grant.displayName || grant.username || `${grant.principalType} · ${grant.principalId}` }} <small v-if="grant.username">{{ grant.username }}</small></td><td>{{ labels[grant.source] || grant.source }}</td><td>{{ grant.expiresAt ? new Date(grant.expiresAt).toLocaleString('zh-CN') : '长期有效' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <button
        v-if="hasMore"
        type="button"
        :disabled="loading"
        @click="load(true)"
      >
        加载更多
      </button>
      <p class="note">
        历史来源待确认的记录保留原有访问，需要核实后处理。此处展示空间授权记录，具体文档仍受其自身权限限制。
      </p>
    </template>
  </section>
</template>
<style scoped>
.sources { padding: 22px; min-width: 0; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; }
.toolbar { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:12px; }
input { min-height:40px; width:min(300px,100%); padding:0 10px; border:1px solid var(--color-border); border-radius:var(--radius-control); }
.table-wrap { overflow-x:auto; } table { width:100%; border-collapse:collapse; } th,td { padding:12px 8px; border-bottom:1px solid var(--color-border-soft); text-align:left; overflow-wrap:anywhere; } th,.note,small { color:var(--color-muted); font-size:13px; } small { display:block; }
.sr-only { position:absolute; width:1px; height:1px; overflow:hidden; clip:rect(0,0,0,0); white-space:nowrap; }
</style>
