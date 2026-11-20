<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

import { ApiRequestError } from '../api/http'
import type { AdminUser } from '../api/admin'
import { spacesApi, type MemberCandidate, type SpaceMember, type SpaceRole } from '../api/spaces'

const props = defineProps<{ spaceId: string, users?: AdminUser[], searchUsers?: boolean }>()
const emit = defineEmits<{ changed: [] }>()

const roleOptions: Array<{ value: SpaceRole, label: string }> = [
  { value: 'SPACE_ADMIN', label: '空间管理员' },
  { value: 'EDITOR', label: '编辑者' },
  { value: 'REVIEWER_PUBLISHER', label: '审核发布者' },
  { value: 'READER', label: '只读成员' },
]
const roleLabels: Record<SpaceRole, string> = Object.fromEntries(
  roleOptions.map(({ value, label }) => [value, label]),
) as Record<SpaceRole, string>

const members = ref<SpaceMember[]>([])
const loading = ref(true)
const loadError = ref('')
const moreError = ref('')
const loadingMore = ref(false)
const memberPage = ref(1)
const hasMoreMembers = ref(false)
let listGeneration = 0
const mutationError = ref('')
const pendingOperation = ref('')
const memberUserId = ref('')
const memberSearch = ref('')
const candidates = ref<MemberCandidate[]>([])
const candidatePage = ref(1)
const candidateHasMore = ref(false)
const candidateLoading = ref(false)
const candidateError = ref('')
let candidateQuery = ''
let candidateGeneration = 0
const memberRole = ref<SpaceRole>('READER')
const operationKeys = new Map<string, string>()

function message(error: unknown, fallback: string) {
  return error instanceof ApiRequestError ? error.displayMessage : fallback
}

async function searchCandidates(more = false) {
  const query = more ? candidateQuery : memberSearch.value.trim()
  if (!query || candidateLoading.value) return
  const generation = ++candidateGeneration
  const page = more ? candidatePage.value + 1 : 1
  candidateLoading.value = true
  candidateError.value = ''
  if (!more) { candidates.value = []; memberUserId.value = ''; candidateQuery = query }
  try {
    const result = await spacesApi.searchCandidates(props.spaceId, query, page)
    if (generation !== candidateGeneration) return
    candidates.value = more ? [...candidates.value, ...result] : result
    candidatePage.value = page
    candidateHasMore.value = result.length === 20
    if (candidates.value.length === 0) candidateError.value = '没有找到匹配的启用用户。'
  } catch (failure) {
    if (generation === candidateGeneration) candidateError.value = message(failure, '搜索用户失败，请重试。')
  } finally { if (generation === candidateGeneration) candidateLoading.value = false }
}

function upsertMember(member: SpaceMember) {
  const index = members.value.findIndex((candidate) => candidate.userId === member.userId)
  if (member.roles.length === 0) {
    if (index >= 0) members.value.splice(index, 1)
    return
  }
  if (index >= 0) members.value.splice(index, 1, member)
  else members.value.push(member)
  members.value.sort((left, right) => left.username.localeCompare(right.username))
}

async function loadMembers() {
  const generation = ++listGeneration
  loadingMore.value = false
  loading.value = true
  loadError.value = ''
  moreError.value = ''
  try {
    const result = await spacesApi.listMembers(props.spaceId)
    if (generation !== listGeneration) return
    members.value = result
    memberPage.value = 1
    hasMoreMembers.value = result.length === 100
  } catch (error) {
    if (generation === listGeneration) loadError.value = message(error, '加载空间成员失败，请稍后重试')
  } finally {
    if (generation === listGeneration) loading.value = false
  }
}

async function loadMoreMembers() {
  if (loadingMore.value || !hasMoreMembers.value) return
  const generation = listGeneration
  loadingMore.value = true
  moreError.value = ''
  try {
    const result = await spacesApi.listMembers(props.spaceId, memberPage.value + 1)
    if (generation !== listGeneration) return
    result.forEach(upsertMember)
    memberPage.value++
    hasMoreMembers.value = result.length === 100
  } catch (error) {
    if (generation !== listGeneration) return
    if (error instanceof ApiRequestError && [401, 403, 404].includes(error.status)) {
      members.value = []; hasMoreMembers.value = false
      loadError.value = message(error, '无法加载空间成员')
    } else moreError.value = message(error, '加载更多成员失败，请重试')
  } finally { if (generation === listGeneration) loadingMore.value = false }
}

async function mutate(
  operation: string,
  action: (key: string) => Promise<SpaceMember>,
) {
  if (pendingOperation.value) return undefined
  const key = operationKeys.get(operation) ?? crypto.randomUUID()
  operationKeys.set(operation, key)
  pendingOperation.value = operation
  mutationError.value = ''
  try {
    const result = await action(key)
    operationKeys.delete(operation)
    return result
  } catch (error) {
    const failureMessage = message(error, '成员操作失败，请稍后重试')
    if (error instanceof ApiRequestError && error.status < 500) {
      operationKeys.delete(operation)
      if (error.status === 409) await loadMembers()
    }
    mutationError.value = failureMessage
    return undefined
  } finally {
    pendingOperation.value = ''
  }
}

async function grantRole() {
  const userId = memberUserId.value.trim()
  const role = memberRole.value
  const member = await mutate(`grant:${userId}:${role}`, (key) =>
    spacesApi.grantRole(props.spaceId, userId, role, key))
  if (!member) return
  upsertMember(member)
  emit('changed')
  memberUserId.value = ''
  memberRole.value = 'READER'
}

async function revokeRole(member: SpaceMember, role: SpaceRole) {
  const updated = await mutate(`revoke:${member.userId}:${role}`, (key) =>
    spacesApi.revokeRole(props.spaceId, member.userId, role, key))
  if (updated) { upsertMember(updated); emit('changed') }
}

onMounted(loadMembers)
onBeforeUnmount(() => { listGeneration++; candidateGeneration++ })
</script>

<template>
  <section
    class="member-panel"
    aria-labelledby="member-panel-title"
  >
    <div class="section-heading">
      <div>
        <h3 id="member-panel-title">
          成员与角色管理
        </h3>
        <p v-if="!users">
          输入内部用户 ID 并授予最小必要角色。
        </p>
      </div>
    </div>

    <form
      class="grant-form"
      data-form="grant-member"
      @submit.prevent="grantRole"
    >
      <label v-if="searchUsers && !users">搜索用户<input
        v-model="memberSearch"
        name="memberSearch"
        placeholder="输入姓名或账号"
        @keydown.enter.prevent="searchCandidates()"
      ></label>
      <button
        v-if="searchUsers && !users"
        data-action="search-candidates"
        type="button"
        :disabled="!memberSearch.trim() || candidateLoading"
        @click="searchCandidates()"
      >
        搜索
      </button>
      <label v-if="users || searchUsers">用户<select
        v-model="memberUserId"
        name="memberUserId"
        required
      >
        <option
          value=""
          disabled
        >选择用户</option>
        <option
          v-for="user in users"
          :key="user.id"
          :value="user.id"
          :disabled="!user.enabled"
        >{{ user.displayName }}（{{ user.username }}）{{ user.enabled ? '' : ' · 已停用' }}</option>
        <option
          v-for="candidate in candidates"
          :key="candidate.userId"
          :value="candidate.userId"
        >{{ candidate.displayName }}（{{ candidate.username }}）</option>
      </select></label>
      <label v-else>用户 ID<input
        v-model="memberUserId"
        name="memberUserId"
        autocomplete="off"
        placeholder="UUID"
        required
      ></label>
      <label>空间角色<select
        v-model="memberRole"
        name="memberRole"
      >
        <option
          v-for="option in roleOptions"
          :key="option.value"
          :value="option.value"
        >{{ option.label }}</option>
      </select></label>
      <button
        class="button button-primary"
        type="submit"
        :disabled="Boolean(pendingOperation)"
      >
        {{ pendingOperation.startsWith('grant:') ? '正在授予…' : '授予角色' }}
      </button>
    </form>

    <p
      v-if="candidateError"
      role="status"
    >
      {{ candidateError }}
    </p>
    <button
      v-if="candidateHasMore"
      type="button"
      :disabled="candidateLoading"
      @click="searchCandidates(true)"
    >
      加载更多用户
    </button>

    <div
      v-if="mutationError"
      class="state-panel state-error compact-state"
      role="alert"
    >
      {{ mutationError }}
    </div>

    <div
      v-if="loading"
      class="state-panel"
      aria-live="polite"
    >
      正在加载成员…
    </div>
    <div
      v-else-if="loadError"
      class="state-panel state-error"
      role="alert"
    >
      <strong>无法加载成员</strong><span>{{ loadError }}</span><button
        data-action="retry-members"
        type="button"
        @click="loadMembers"
      >
        重试
      </button>
    </div>
    <div
      v-else-if="members.length === 0"
      class="state-panel"
    >
      <strong>暂无空间成员</strong><span>授予角色后，成员会出现在这里。</span><button
        data-action="reload-members"
        type="button"
        @click="loadMembers"
      >
        刷新
      </button>
    </div>
    <div
      v-else
      class="table-wrap"
    >
      <table>
        <thead>
          <tr>
            <th class="column-member">
              成员
            </th><th class="column-status">
              状态
            </th><th class="column-roles">
              角色与操作
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="member in members"
            :key="member.userId"
          >
            <td>
              <strong
                data-member-name
                :title="member.displayName"
              >{{ member.displayName }}</strong>
              <span
                data-member-username
                :title="member.username"
              >{{ member.username }}</span>
              <small :title="member.userId">{{ member.userId }}</small>
            </td>
            <td><span :class="['status-tag', { disabled: !member.enabled }]">{{ member.enabled ? '已启用' : '已停用' }}</span></td>
            <td>
              <div class="role-list">
                <span
                  v-for="role in member.roles"
                  :key="role"
                  class="role-chip"
                >
                  {{ roleLabels[role] }}
                  <button
                    data-action="revoke-role"
                    :data-role="role"
                    type="button"
                    :aria-label="`撤销${roleLabels[role]}`"
                    :disabled="Boolean(pendingOperation)"
                    @click="revokeRole(member, role)"
                  >撤销</button>
                </span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p
      v-if="moreError"
      class="state-panel state-error compact-state"
      role="alert"
    >
      {{ moreError }}
    </p>
    <button
      v-if="hasMoreMembers && !loading && !loadError"
      class="button"
      data-action="more-members"
      type="button"
      :disabled="loadingMore || Boolean(pendingOperation)"
      @click="loadMoreMembers"
    >
      {{ loadingMore ? '正在加载…' : moreError ? '重试加载更多' : '加载更多成员' }}
    </button>
  </section>
</template>

<style scoped>
.member-panel { display: grid; gap: 18px; min-width: 0; padding: 22px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
.section-heading h3 { margin: 4px 0 6px; }
.section-heading p:last-child { margin: 0; color: var(--color-muted); }
.eyebrow { margin: 0; color: var(--color-accent); font-size: 12px; font-weight: 800; letter-spacing: .12em; }
.grant-form { display: grid; grid-template-columns: minmax(260px, 1fr) minmax(180px, 240px) auto; align-items: end; gap: 12px; }
label { display: grid; gap: 7px; min-width: 0; color: var(--color-muted); font-size: 13px; font-weight: 700; }
input, select { width: 100%; min-height: 42px; padding: 0 11px; border: 1px solid var(--color-border); border-radius: var(--radius-control); color: var(--color-ink); background: #fff; }
.button { display: inline-flex; align-items: center; justify-content: center; min-height: 42px; padding: 0 17px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); font-weight: 750; appearance: none; cursor: pointer; }
.button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; }
.button-primary:hover { border-color: var(--color-primary-strong); background: var(--color-primary-strong); }
.button:focus-visible, button:focus-visible, input:focus-visible, select:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.button:disabled, button:disabled { opacity: .55; cursor: not-allowed; }
.state-panel { display: grid; justify-items: start; gap: 7px; padding: 24px; border: 1px solid var(--color-border); border-radius: 12px; color: var(--color-muted); background: #f9fbfc; }
.state-panel strong { color: var(--color-ink); }
.state-error { border-color: #edb8c0; background: #fff8f8; }
.compact-state { padding: 14px 16px; }
.state-panel button { min-height: 36px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: 8px; color: var(--color-ink); background: #fff; cursor: pointer; }
.table-wrap { max-width: 100%; overflow-x: auto; }
table { width: 100%; min-width: 760px; border-collapse: collapse; table-layout: fixed; }
th, td { padding: 13px 10px; overflow: hidden; border-bottom: 1px solid var(--color-border-soft); text-align: left; vertical-align: top; }
th { color: var(--color-muted); font-size: 12px; }
.column-member { width: 40%; } .column-status { width: 110px; } .column-roles { width: auto; }
td strong, td > span, td small { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
td > span, td small { margin-top: 4px; color: var(--color-muted); }
td small { font-family: var(--font-mono); }
.status-tag { display: inline-block; padding: 4px 8px; border-radius: 999px; color: #1e6653; background: #e1f4ec; font-size: 12px; font-weight: 750; }
.status-tag.disabled { color: #75505a; background: #f4e7ea; }
.role-list { display: flex; flex-wrap: wrap; gap: 8px; }
.role-chip { display: inline-flex; align-items: center; gap: 7px; padding: 5px 6px 5px 9px; border-radius: 999px; color: #26546d; background: #e6f1f7; font-size: 12px; font-weight: 750; }
.role-chip button { min-height: 26px; padding: 0 7px; border: 0; border-radius: 999px; color: #6e3f4b; background: #fff; cursor: pointer; }
@media (max-width: 780px) { .grant-form { grid-template-columns: 1fr; } .member-panel { padding: 17px; } }
</style>
