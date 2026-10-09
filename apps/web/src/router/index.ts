import { createRouter, createWebHistory, type RouterHistory } from 'vue-router'

import { useSessionStore } from '../stores/session'
import DocumentDetailView from '../views/DocumentDetailView.vue'
import KnowledgeHomeView from '../views/KnowledgeHomeView.vue'
import LoginView from '../views/LoginView.vue'
import UploadView from '../views/UploadView.vue'

export function createAppRouter(history: RouterHistory = createWebHistory()) {
  const router = createRouter({
    history,
    routes: [
      {
        path: '/login',
        name: 'login',
        component: LoginView,
        meta: { public: true },
      },
      {
        path: '/',
        name: 'knowledge-home',
        component: KnowledgeHomeView,
      },
      {
        path: '/documents/:documentId',
        name: 'document-detail',
        component: DocumentDetailView,
      },
      {
        path: '/upload',
        name: 'document-upload',
        component: UploadView,
      },
    ],
  })

  router.beforeEach(async (to) => {
    const session = useSessionStore()
    await session.bootstrap()
    if (to.meta.public) {
      if (to.name === 'login' && session.authenticated) {
        return safeRedirect(to.query.redirect) ?? { name: 'knowledge-home' }
      }
      return true
    }
    if (!session.authenticated) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    return true
  })

  return router
}

export function safeRedirect(value: unknown): string | null {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')
    ? value
    : null
}

export default createAppRouter()
