<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiRequestError } from '../api/http'
import { useSessionStore } from '../stores/session'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const submitting = ref(false)
const submitError = ref('')
const visibleError = computed(() => submitError.value || session.bootstrapError)

function safeRedirect(value: unknown): string | null {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')
    ? value
    : null
}

async function submit() {
  if (submitting.value) return
  submitting.value = true
  submitError.value = ''
  try {
    await session.login(username.value, password.value)
    password.value = ''
    await router.replace(session.passwordChangeRequired ? '/change-password' : safeRedirect(route.query.redirect) ?? '/workbench')
  } catch (error) {
    submitError.value = error instanceof ApiRequestError
      ? error.displayMessage
      : '登录失败，请稍后重试'
  } finally {
    password.value = ''
    submitting.value = false
  }
}
</script>

<template>
  <section
    class="login-page"
    aria-labelledby="login-title"
  >
    <div class="login-intro">
      <span
        class="login-mark"
        aria-hidden="true"
      >云</span>
      <p class="eyebrow">
        YUNECT · KNOWLEDGE
      </p>
      <h1 id="login-title">
        登录企业知识库
      </h1>
      <p>沉淀组织知识资产，在可信权限边界内检索、评审与复用。</p>
    </div>
    <form
      class="login-panel"
      @submit.prevent="submit"
    >
      <div>
        <h2>账号登录</h2>
        <p>使用管理员分配的本地账号。</p>
      </div>
      <label>
        <span>用户名</span>
        <input
          v-model.trim="username"
          name="username"
          type="text"
          autocomplete="username"
          maxlength="128"
          required
          autofocus
        >
      </label>
      <label>
        <span>密码</span>
        <input
          v-model="password"
          name="password"
          type="password"
          autocomplete="current-password"
          maxlength="128"
          required
        >
      </label>
      <p
        v-if="route.query.passwordChanged === '1'"
        role="status"
      >
        密码已修改，请使用新密码登录。
      </p>
      <p
        v-if="visibleError"
        class="login-error"
        role="alert"
      >
        {{ visibleError }}
      </p>
      <button
        class="primary-action"
        type="submit"
        :disabled="submitting"
      >
        {{ submitting ? '正在登录…' : '登录' }}
      </button>
      <p class="security-note">
        会话仅保存在当前浏览器的安全 Cookie 中，不提供“记住我”。
      </p>
    </form>
  </section>
</template>

<style scoped>
.login-page { display: grid; grid-template-columns: minmax(0, 1fr) minmax(340px, 460px); align-items: center; gap: clamp(42px, 7vw, 110px); min-height: calc(100vh - 68px); max-width: 1120px; margin: 0 auto; }
.login-intro { min-width: 0; }
.login-mark { display: grid; width: 58px; height: 58px; margin-bottom: 24px; border-radius: 17px; color: #fff; background: var(--color-accent); box-shadow: 0 14px 30px rgb(22 116 134 / 24%); font-size: 28px; font-weight: 850; place-items: center; }
.eyebrow { margin: 0 0 8px; color: var(--color-accent); font-size: 12px; font-weight: 800; letter-spacing: 0.16em; }
.login-intro h1 { margin: 0; font-size: clamp(34px, 5vw, 56px); line-height: 1.08; letter-spacing: -0.04em; }
.login-intro > p:last-child { max-width: 580px; margin: 22px 0 0; color: var(--color-muted); font-size: 17px; line-height: 1.8; }
.login-panel { display: grid; gap: 20px; min-width: 0; padding: clamp(28px, 4vw, 42px); border: 1px solid var(--color-border-soft); border-radius: var(--radius-panel); background: #fff; box-shadow: var(--shadow-panel); }
.login-panel h2 { margin: 0; font-size: 24px; }
.login-panel div > p { margin: 7px 0 0; color: var(--color-muted); font-size: 14px; }
label { display: grid; gap: 8px; color: var(--color-ink); font-size: 14px; font-weight: 750; }
input { width: 100%; min-width: 0; height: 46px; padding: 0 13px; border: 1px solid var(--color-border); border-radius: var(--radius-control); color: var(--color-ink); background: #fff; appearance: none; }
input:focus-visible { outline: 3px solid var(--color-focus); border-color: var(--color-accent); }
.primary-action { width: 100%; min-height: 46px; border: 1px solid var(--color-primary); border-radius: var(--radius-control); color: #fff; background: var(--color-primary); -webkit-text-fill-color: #fff; appearance: none; font-weight: 800; cursor: pointer; }
.primary-action:hover:not(:disabled) { border-color: var(--color-primary-strong); background: var(--color-primary-strong); }
.primary-action:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.primary-action:disabled { border-color: #8298a2; color: #fff; background: #8298a2; -webkit-text-fill-color: #fff; opacity: 0.72; cursor: wait; }
.login-error { margin: -4px 0 0; padding: 11px 13px; border: 1px solid #efc5c1; border-radius: 8px; color: #8a2922; background: #fff3f1; font-size: 13px; line-height: 1.55; overflow-wrap: anywhere; }
.security-note { margin: -5px 0 0; color: var(--color-muted); font-size: 12px; line-height: 1.55; text-align: center; }
@media (max-width: 820px) { .login-page { grid-template-columns: 1fr; align-content: center; gap: 34px; min-height: auto; padding-block: 28px; } .login-intro { text-align: center; } .login-mark { margin-inline: auto; } .login-intro > p:last-child { margin-inline: auto; } .login-panel { width: min(460px, 100%); margin: 0 auto; } }
</style>
