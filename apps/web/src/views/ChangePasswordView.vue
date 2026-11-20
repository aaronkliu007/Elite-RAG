<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { ApiRequestError } from '../api/http'

const session = useSessionStore()
const router = useRouter()
const currentPassword = ref('')
const newPassword = ref('')
const confirmation = ref('')
const error = ref('')
const busy = ref(false)

async function submit() {
  if (busy.value) return
  if (newPassword.value !== confirmation.value) { error.value = '两次输入的新密码不一致'; return }
  if (newPassword.value.length < 12 || newPassword.value.length > 128) { error.value = '新密码长度须为 12 到 128 个字符'; return }
  busy.value = true
  error.value = ''
  try {
    await session.changeInitialPassword(currentPassword.value, newPassword.value)
    await router.replace('/login?passwordChanged=1')
  } catch (failure) {
    error.value = failure instanceof ApiRequestError ? failure.displayMessage : '修改失败，请稍后重试'
  } finally {
    currentPassword.value = ''
    newPassword.value = ''
    confirmation.value = ''
    busy.value = false
  }
}

async function logout() {
  try { await session.logout() } finally { await router.replace('/login') }
}
</script>

<template>
  <main class="change-password">
    <form @submit.prevent="submit">
      <h1>修改初始密码</h1>
      <p>首次登录需要修改密码，完成后请重新登录。</p>
      <label>当前密码<input
        v-model="currentPassword"
        type="password"
        autocomplete="current-password"
        required
      ></label>
      <label>新密码<input
        v-model="newPassword"
        type="password"
        autocomplete="new-password"
        minlength="12"
        maxlength="128"
        required
      ></label>
      <label>确认新密码<input
        v-model="confirmation"
        type="password"
        autocomplete="new-password"
        minlength="12"
        maxlength="128"
        required
      ></label>
      <p
        v-if="error"
        role="alert"
        class="error"
      >
        {{ error }}
      </p>
      <button
        type="submit"
        :disabled="busy"
      >
        {{ busy ? '正在修改…' : '修改密码' }}
      </button>
      <button
        type="button"
        class="secondary"
        @click="logout"
      >
        退出登录
      </button>
    </form>
  </main>
</template>

<style scoped>
.change-password { min-height:100vh; display:grid; place-items:center; padding:24px; }
form { width:min(440px,100%); display:grid; gap:16px; padding:28px; border:1px solid var(--color-border); border-radius:var(--radius-panel); background:white; }
h1 { margin:0; font-size:22px; } p { margin:0; color:var(--color-muted); } label { display:grid; gap:6px; font-weight:700; }
input { min-height:44px; padding:10px; border:1px solid var(--color-border); border-radius:var(--radius-control); }
button { min-height:44px; border:0; border-radius:var(--radius-control); color:white; background:var(--color-primary); font-weight:700; cursor:pointer; }
button:disabled { opacity:.55; cursor:not-allowed; } :is(input,button):focus-visible { outline:3px solid var(--color-focus); outline-offset:2px; }
.error { color:#a51f32; }
.secondary { background:#fff; color:var(--color-ink); border:1px solid var(--color-border); }
</style>
