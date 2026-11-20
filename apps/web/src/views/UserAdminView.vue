<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'

import { adminApi, type AdminUser } from '../api/admin'
import { ApiRequestError } from '../api/http'
import { useSessionStore } from '../stores/session'
import DingTalkSyncPanel from '../components/DingTalkSyncPanel.vue'

const session = useSessionStore()
const users = ref<AdminUser[]>([])
const loading = ref(true)
const loadError = ref('')
const moreError = ref('')
const loadingMore = ref(false)
const userPage = ref(1)
const hasMoreUsers = ref(false)
const mutationError = ref('')
const pendingOperation = ref('')
const resetUserId = ref('')
const createForm = reactive({ username: '', displayName: '', password: '' })
const resetPassword = ref('')
const createPanelOpen = ref(false)
const createUsernameInput = ref<HTMLInputElement | null>(null)
const userQuery = ref('')
const copyStatus = ref('')
const filteredUsers = computed(() => { const term = userQuery.value.trim().toLocaleLowerCase('zh-CN'); return term ? users.value.filter(user => `${user.displayName} ${user.username}`.toLocaleLowerCase('zh-CN').includes(term)) : users.value })
const operationKeys = new Map<string, string>()
let userListGeneration = 0

function message(error: unknown, fallback: string) {
  return error instanceof ApiRequestError ? error.displayMessage : fallback
}

function upsertUser(user: AdminUser) {
  const index = users.value.findIndex((candidate) => candidate.id === user.id)
  if (index >= 0) users.value.splice(index, 1, user)
  else users.value.push(user)
  users.value.sort((left, right) => left.username.localeCompare(right.username))
}

function cannotDisableSelf(user: AdminUser) {
  return user.enabled && user.id === session.user?.userId
}
function isDingTalkUser(user: AdminUser) { return user.username.startsWith('dingtalk:user:') }
async function copyUserId(user: AdminUser) {
  try {
    if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(user.id)
    copyStatus.value = `已复制用户 ID：${user.displayName}`
  } catch { copyStatus.value = '复制失败，请手动选择并复制用户 ID。' }
}

async function loadUsers() {
  const generation = ++userListGeneration
  loadingMore.value = false
  loading.value = true
  loadError.value = ''
  moreError.value = ''
  try {
    const result = await adminApi.listUsers()
    if (generation !== userListGeneration) return
    users.value = result
    userPage.value = 1
    hasMoreUsers.value = users.value.length === 100
  } catch (error) {
    if (generation === userListGeneration) loadError.value = message(error, '加载用户失败，请稍后重试')
  } finally {
    if (generation === userListGeneration) loading.value = false
  }
}

async function loadMoreUsers() {
  if (loadingMore.value || !hasMoreUsers.value) return
  const generation = userListGeneration
  loadingMore.value = true
  moreError.value = ''
  try {
    const next = await adminApi.listUsers(userPage.value + 1)
    if (generation !== userListGeneration) return
    next.forEach(upsertUser)
    userPage.value++
    hasMoreUsers.value = next.length === 100
  } catch (failure) {
    if (generation !== userListGeneration) return
    if (failure instanceof ApiRequestError && [401, 403, 404].includes(failure.status)) {
      users.value = []
      hasMoreUsers.value = false
      loadError.value = message(failure, '无法加载用户')
    } else moreError.value = message(failure, '加载更多用户失败，请重试')
  } finally { if (generation === userListGeneration) loadingMore.value = false }
}

async function mutate<T>(operation: string, action: (key: string) => Promise<T>) {
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
    mutationError.value = message(error, '操作失败，请稍后重试')
    if (error instanceof ApiRequestError && error.status < 500) {
      operationKeys.delete(operation)
      if (error.status === 409) await loadUsers()
    }
    return undefined
  } finally {
    pendingOperation.value = ''
  }
}

async function openCreateUser() { createPanelOpen.value = true; await nextTick(); createUsernameInput.value?.focus() }
function closeCreateUser() { createForm.password = ''; createPanelOpen.value = false }

async function createUser() {
  const created = await mutate('create-user', (key) => adminApi.createUser({ ...createForm }, key))
  createForm.password = ''
  if (!created) return
  upsertUser(created)
  createForm.username = ''
  createForm.displayName = ''
  createPanelOpen.value = false
}

async function toggleUser(user: AdminUser) {
  const enabled = !user.enabled
  const result = await mutate(`enabled:${user.id}:${enabled}`, (key) =>
    adminApi.setEnabled(user.id, enabled, key))
  if (result) upsertUser({ ...user, enabled, updatedAt: new Date().toISOString() })
}

function openReset(userId: string) {
  resetUserId.value = userId
  resetPassword.value = ''
  mutationError.value = ''
}
function cancelReset() { resetUserId.value = ''; resetPassword.value = ''; mutationError.value = '' }

async function submitReset() {
  const userId = resetUserId.value
  const result = await mutate(`reset:${userId}`, (key) =>
    adminApi.resetPassword(userId, resetPassword.value, key))
  resetPassword.value = ''
  if (result) resetUserId.value = ''
}

onMounted(loadUsers)
onBeforeUnmount(() => { userListGeneration++ })
</script>

<template>
  <section class="admin-view">
    <DingTalkSyncPanel @synced="loadUsers" />
    <header
      v-if="loading || loadError || users.length === 0"
      class="page-header"
    >
      <button
        class="button button-primary"
        data-action="open-create-user"
        type="button"
        :aria-expanded="createPanelOpen"
        aria-controls="create-user-panel"
        @click="openCreateUser"
      >
        ＋ 新建用户
      </button>
    </header>

    <form
      v-show="createPanelOpen"
      id="create-user-panel"
      class="create-card"
      data-form="create-user"
      @submit.prevent="createUser"
    >
      <div class="section-heading">
        <div>
          <h3>创建用户</h3>
          <p>用户名用于登录，显示名用于知识资产协作。</p>
        </div>
      </div>
      <div class="form-grid">
        <label>用户名<input
          ref="createUsernameInput"
          v-model.trim="createForm.username"
          name="username"
          autocomplete="off"
          required
        ></label>
        <label>显示名<input
          v-model.trim="createForm.displayName"
          name="displayName"
          autocomplete="off"
          required
        ></label>
        <label>初始密码<input
          v-model="createForm.password"
          name="password"
          type="password"
          autocomplete="new-password"
          minlength="12"
          required
        ></label>
        <button
          class="button button-primary"
          type="submit"
          :disabled="Boolean(pendingOperation)"
        >
          {{ pendingOperation === 'create-user' ? '正在创建…' : '创建用户' }}
        </button>
        <button
          class="button button-secondary"
          type="button"
          :disabled="Boolean(pendingOperation)"
          @click="closeCreateUser"
        >
          收起
        </button>
      </div>
    </form>

    <div
      v-if="mutationError"
      class="state-panel state-error"
      role="alert"
    >
      {{ mutationError }}
    </div>

    <div
      v-if="loading"
      class="state-panel"
      aria-live="polite"
    >
      正在加载用户…
    </div>
    <div
      v-else-if="loadError"
      class="state-panel state-error"
      role="alert"
    >
      <strong>无法加载用户</strong><span>{{ loadError }}</span><button
        data-action="retry-users"
        type="button"
        @click="loadUsers"
      >
        重试
      </button>
    </div>
    <div
      v-else-if="users.length === 0"
      class="state-panel"
    >
      <strong>暂无用户</strong><span>创建首个业务用户后即可分配空间角色。</span>
    </div>
    <section
      v-else
      class="table-card"
      aria-labelledby="user-list-title"
    >
      <div class="section-heading">
        <h3 id="user-list-title">
          用户列表
        </h3>
        <button
          class="button button-primary"
          data-action="open-create-user"
          type="button"
          :aria-expanded="createPanelOpen"
          aria-controls="create-user-panel"
          @click="openCreateUser"
        >
          ＋ 新建用户
        </button>
      </div>
      <label class="user-search"><span class="sr-only">搜索已加载用户</span><input
        v-model="userQuery"
        type="search"
        placeholder="搜索已加载的用户名或显示名"
      ></label>
      <p
        v-if="copyStatus"
        data-copy-status
        role="status"
      >
        {{ copyStatus }}
      </p>
      <p
        v-if="filteredUsers.length === 0"
        class="search-empty"
        role="status"
      >
        没有匹配的用户，请调整搜索条件。
      </p>
      <div
        v-else
        class="table-wrap"
      >
        <table>
          <thead>
            <tr>
              <th class="column-name">
                用户
              </th><th class="column-status">
                状态
              </th><th class="column-date">
                更新时间
              </th><th class="column-action">
                操作
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="user in filteredUsers"
              :key="user.id"
            >
              <td>
                <strong
                  data-user-name
                  :title="user.displayName"
                >{{ user.displayName }}</strong>
                <span
                  v-if="!isDingTalkUser(user)"
                  data-user-username
                  :title="user.username"
                >{{ user.username }}</span>
                <span v-else>钉钉成员</span>
                <small :title="user.id">{{ user.id }}</small>
              </td>
              <td><span :class="['status-tag', { disabled: !user.enabled }]">{{ user.enabled ? '已启用' : '已停用' }}</span></td>
              <td>{{ new Date(user.updatedAt).toLocaleDateString('zh-CN') }}</td>
              <td>
                <div class="row-actions">
                  <button
                    data-action="copy-user-id"
                    type="button"
                    @click="copyUserId(user)"
                  >
                    复制用户 ID
                  </button>
                  <button
                    v-if="!isDingTalkUser(user)"
                    data-action="toggle-user"
                    type="button"
                    :disabled="Boolean(pendingOperation) || cannotDisableSelf(user)"
                    :title="cannotDisableSelf(user) ? '不能停用当前登录用户' : undefined"
                    @click="toggleUser(user)"
                  >
                    {{ user.enabled ? '停用' : '启用' }}
                  </button>
                  <button
                    v-if="!isDingTalkUser(user)"
                    data-action="show-reset"
                    type="button"
                    :disabled="Boolean(pendingOperation)"
                    @click="openReset(user.id)"
                  >
                    重置密码
                  </button>
                </div>
                <form
                  v-if="resetUserId === user.id"
                  class="reset-form"
                  data-form="reset-password"
                  @submit.prevent="submitReset"
                >
                  <label><span class="sr-only">新密码</span><input
                    v-model="resetPassword"
                    name="resetPassword"
                    type="password"
                    autocomplete="new-password"
                    minlength="12"
                    placeholder="至少 12 位新密码"
                    required
                  ></label>
                  <button
                    class="button button-primary compact"
                    type="submit"
                    :disabled="Boolean(pendingOperation)"
                  >
                    确认重置
                  </button>
                  <button
                    class="button button-secondary compact"
                    data-action="cancel-reset"
                    type="button"
                    :disabled="Boolean(pendingOperation)"
                    @click="cancelReset"
                  >
                    取消
                  </button>
                </form>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p
        v-if="moreError"
        class="state-panel state-error"
        role="alert"
      >
        {{ moreError }}
      </p>
      <button
        v-if="hasMoreUsers"
        class="button button-secondary"
        data-action="more-users"
        type="button"
        :disabled="loadingMore || Boolean(pendingOperation)"
        @click="loadMoreUsers"
      >
        {{ loadingMore ? '正在加载…' : moreError ? '重试加载更多' : '加载更多用户' }}
      </button>
    </section>
  </section>
</template>

<style scoped>
.admin-view { display: grid; gap: 22px; min-width: 0; }
.page-header h2 { margin: 4px 0 8px; font-size: clamp(26px, 3vw, 36px); }
.page-header p:last-child, .section-heading p { margin: 0; color: var(--color-muted); }
.eyebrow { margin: 0; color: var(--color-accent); font-size: 12px; font-weight: 800; letter-spacing: .12em; }
.create-card, .table-card { min-width: 0; padding: 22px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.section-heading h3 { margin: 0 0 4px; }
.section-heading > span { color: var(--color-muted); }
.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)) auto; align-items: end; gap: 14px; }
label { display: grid; gap: 7px; min-width: 0; color: var(--color-muted); font-size: 13px; font-weight: 700; }
input { width: 100%; min-height: 42px; padding: 0 11px; border: 1px solid var(--color-border); border-radius: var(--radius-control); color: var(--color-ink); background: #fff; }
.button { display: inline-flex; align-items: center; justify-content: center; min-height: 42px; padding: 0 17px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); font-weight: 750; appearance: none; cursor: pointer; }
.button-primary { color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; }
.button-primary:hover { border-color: var(--color-primary-strong); background: var(--color-primary-strong); }
.button:focus-visible, button:focus-visible, input:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.button:disabled, button:disabled { opacity: .55; cursor: not-allowed; }
.compact { min-height: 38px; white-space: nowrap; }
.state-panel { display: grid; justify-items: start; gap: 8px; padding: 26px; border: 1px solid var(--color-border); border-radius: var(--radius-panel); color: var(--color-muted); background: #fff; }
.state-panel strong { color: var(--color-ink); }
.state-error { border-color: #edb8c0; background: #fff8f8; }
.state-panel button, .row-actions button { min-height: 36px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: 8px; color: var(--color-ink); background: #fff; cursor: pointer; }
.table-wrap { max-width: 100%; overflow-x: auto; }
table { width: 100%; min-width: 850px; border-collapse: collapse; table-layout: fixed; }
th, td { padding: 13px 10px; overflow: hidden; border-bottom: 1px solid var(--color-border-soft); text-align: left; vertical-align: top; }
th { color: var(--color-muted); font-size: 12px; }
.column-name { width: 38%; } .column-status { width: 110px; } .column-date { width: 130px; } .column-action { width: 310px; }
td strong, td span, td small { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
td span, td small { margin-top: 4px; color: var(--color-muted); }
td small { font-family: var(--font-mono); }
.status-tag { display: inline-block; padding: 4px 8px; border-radius: 999px; color: #1e6653; background: #e1f4ec; font-size: 12px; font-weight: 750; }
.status-tag.disabled { color: #75505a; background: #f4e7ea; }
.row-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.reset-form { display: grid; grid-template-columns: minmax(150px, 1fr) auto; gap: 8px; margin-top: 10px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 1000px) { .form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 620px) { .form-grid { grid-template-columns: 1fr; } .create-card, .table-card { padding: 17px; } }
.user-search { display:block;max-width:360px;margin:0 0 16px; }.user-search input { width:100%;min-height:42px;padding:0 12px;border:1px solid var(--color-border);border-radius:var(--radius-control); }.search-empty { color:var(--color-muted);padding:20px 0; }.button-secondary { color:var(--color-primary);background:#fff; }.button-secondary:hover { background:#eef3ff; }.user-search input:focus-visible { outline:3px solid var(--color-focus);outline-offset:2px; }
</style>
