<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import type { AdminUser } from '../api/admin'
import { spacesApi, type EffectiveUserPermission, type MemberCandidate, type SpaceRole } from '../api/spaces'

const props = defineProps<{ spaceId: string, spaces: Array<{ id: string, name: string }>, users?: AdminUser[] }>()
const selectedUser = ref('')
const search = ref('')
const candidates = ref<MemberCandidate[]>([])
const results = ref<Array<{ spaceId: string, spaceName: string, permission: EffectiveUserPermission }>>([])
const searching = ref(false)
const loading = ref(false)
const error = ref('')
let generation = 0
const roleLabels: Record<SpaceRole, string> = { SPACE_ADMIN: '空间管理员', EDITOR: '编辑者', REVIEWER_PUBLISHER: '审核发布者', READER: '只读成员' }
const sourceLabels: Record<string, string> = { DIRECT: '直接授权', SPACE_ROLE: '空间角色', LEGACY_UNKNOWN: '历史来源待确认' }

async function searchUsers() {
  if (!search.value.trim() || searching.value) return
  searching.value = true; error.value = ''; candidates.value = []
  try { candidates.value = await spacesApi.searchCandidates(props.spaceId, search.value.trim(), 1) }
  catch { error.value = '用户搜索失败，请重试。' }
  finally { searching.value = false }
}
async function load() {
  const current = ++generation
  results.value = []; error.value = ''
  if (!selectedUser.value) return
  loading.value = true
  try {
    const found = await Promise.all(props.spaces.map(async (space) => ({ spaceId: space.id, spaceName: space.name,
      permission: await spacesApi.effectiveUserPermissions(space.id, selectedUser.value) })))
    if (current === generation) results.value = found
  } catch { if (current === generation) error.value = '无法完整加载用户权限，请重试。' }
  finally { if (current === generation) loading.value = false }
}
watch(selectedUser, () => { void load() })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <section
    class="effective-panel"
    aria-label="用户有效权限"
  >
    <div class="controls">
      <label v-if="!users">搜索用户<input
        v-model="search"
        name="effectiveUserSearch"
        placeholder="姓名或账号"
        @keydown.enter.prevent="searchUsers"
      ></label>
      <button
        v-if="!users"
        type="button"
        :disabled="searching || !search.trim()"
        @click="searchUsers"
      >
        搜索
      </button>
      <label>选择用户<select
        v-model="selectedUser"
        name="effectiveUser"
      ><option value="">请选择用户</option>
        <option
          v-for="user in users"
          :key="user.id"
          :value="user.id"
        >{{ user.displayName }}（{{ user.username }}）{{ user.enabled ? '' : ' · 已停用' }}</option>
        <option
          v-for="user in candidates"
          :key="user.userId"
          :value="user.userId"
        >{{ user.displayName }}（{{ user.username }}）</option>
      </select></label>
    </div>
    <p
      v-if="error"
      role="alert"
    >
      {{ error }} <button
        type="button"
        @click="load"
      >
        重试
      </button>
    </p>
    <p
      v-else-if="loading"
      role="status"
    >
      正在计算用户权限…
    </p>
    <p v-else-if="!selectedUser">
      选择用户后查看其在可管理空间中的权限。
    </p>
    <div
      v-else
      class="table-wrap"
    >
      <table>
        <thead><tr><th>知识空间</th><th>空间访问</th><th>系统／空间角色</th><th>授权来源</th></tr></thead><tbody>
          <tr
            v-for="row in results"
            :key="row.spaceId"
          >
            <td>{{ row.spaceName }}</td>
            <td>{{ !row.permission.enabled ? '账号已停用' : row.permission.spaceReadable ? '可访问' : '未获得访问权限' }}</td>
            <td>{{ [...(row.permission.systemAdmin ? ['系统管理员'] : []), ...row.permission.spaceRoles.map(role => roleLabels[role])].join('、') || '无' }}</td>
            <td>
              <span v-if="!row.permission.grants.length">无空间访问授权</span><ul v-else>
                <li
                  v-for="item in row.permission.grants"
                  :key="item.grant.id"
                >
                  {{ item.inherited ? `部门继承：${item.grant.displayName || '所属部门'}` : sourceLabels[item.grant.source] || item.grant.source }} · {{ item.effective ? '生效' : '未生效' }}<template v-if="item.grant.expiresAt">
                    （至 {{ new Date(item.grant.expiresAt).toLocaleString('zh-CN') }}）
                  </template>
                </li>
              </ul>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p class="note">
      这里展示空间级访问及角色；具体文档仍须经过文档权限和发布状态校验。历史来源待确认记录继续按现有授权生效。
    </p>
  </section>
</template>

<style scoped>
.effective-panel { min-width:0; } .controls { display:flex; flex-wrap:wrap; align-items:end; gap:10px; } label { display:grid; gap:6px; font-size:13px; color:var(--color-muted); } input,select { min-height:40px; min-width:220px; max-width:100%; border:1px solid var(--color-border); border-radius:var(--radius-control); padding:0 10px; background:#fff; color:var(--color-ink); } button { min-height:40px; padding:0 12px; border:1px solid var(--color-border); border-radius:var(--radius-control); background:#fff; cursor:pointer; } :is(button,input,select):focus-visible { outline:3px solid var(--color-focus); outline-offset:2px; } .table-wrap { overflow-x:auto; } table { width:100%; border-collapse:collapse; margin-top:12px; } th,td { text-align:left; vertical-align:top; padding:12px 8px; border-bottom:1px solid var(--color-border-soft); overflow-wrap:anywhere; } th,.note { color:var(--color-muted); font-size:13px; } ul { padding-left:18px; margin:0; }
</style>
