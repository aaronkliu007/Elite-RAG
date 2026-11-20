import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { ApiRequestError, clearCsrfToken, configureUnauthorizedHandler } from '../api/http'
import { sessionApi, type SessionUser } from '../api/session'
import { takeQuestionDraft } from '../qaDraft'

export type { SessionUser } from '../api/session'

export const useSessionStore = defineStore('session', () => {
  const user = ref<SessionUser | null>(null)
  const initialized = ref(false)
  const bootstrapError = ref('')
  let bootstrapRequest: Promise<void> | null = null

  const authenticated = computed(() => user.value !== null)
  const passwordChangeRequired = computed(() => user.value?.passwordChangeRequired === true)

  function establish(currentUser: SessionUser) {
    user.value = currentUser
    initialized.value = true
    bootstrapError.value = ''
  }

  function revoke() {
    takeQuestionDraft()
    user.value = null
  }

  configureUnauthorizedHandler(revoke)

  async function bootstrap() {
    if (initialized.value) return
    if (bootstrapRequest) return bootstrapRequest
    bootstrapRequest = (async () => {
      bootstrapError.value = ''
      try {
        await sessionApi.csrf()
        establish(await sessionApi.current())
      } catch (error) {
        revoke()
        if (!(error instanceof ApiRequestError && error.status === 401)) {
          bootstrapError.value = error instanceof ApiRequestError
            ? error.displayMessage
            : '登录服务暂时不可用，请稍后重试'
        }
      } finally {
        initialized.value = true
        bootstrapRequest = null
      }
    })()
    return bootstrapRequest
  }

  async function login(username: string, password: string) {
    establish(await sessionApi.login(username, password))
  }

  async function logout() {
    try {
      await sessionApi.logout()
    } finally {
      revoke()
      clearCsrfToken()
      initialized.value = true
    }
  }

  async function changeInitialPassword(currentPassword: string, newPassword: string) {
    await sessionApi.changeInitialPassword(currentPassword, newPassword)
    revoke()
    clearCsrfToken()
  }

  return {
    user,
    authenticated,
    passwordChangeRequired,
    initialized,
    bootstrapError,
    establish,
    revoke,
    bootstrap,
    login,
    logout,
    changeInitialPassword,
  }
})
