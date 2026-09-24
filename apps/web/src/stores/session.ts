import { defineStore } from 'pinia'

export interface SessionUser {
  id: string
  name: string
  departmentName: string
}

export const useSessionStore = defineStore('session', {
  state: () => ({
    user: null as SessionUser | null,
    authenticated: false,
  }),
  actions: {
    establish(user: SessionUser) {
      this.user = user
      this.authenticated = true
    },
    revoke() {
      this.user = null
      this.authenticated = false
    },
  },
})
