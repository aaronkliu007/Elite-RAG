import { createRouter, createWebHistory, type RouterHistory } from 'vue-router'

import { adminApi } from '../api/admin'
import { useSessionStore } from '../stores/session'
import DocumentDetailView from '../views/DocumentDetailView.vue'
import KnowledgeHomeView from '../views/KnowledgeHomeView.vue'
import LoginView from '../views/LoginView.vue'
import SearchView from '../views/SearchView.vue'
import SpaceDetailView from '../views/SpaceDetailView.vue'
import UploadView from '../views/UploadView.vue'
import UserAdminView from '../views/UserAdminView.vue'

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
      {
        path: '/search',
        name: 'search',
        component: SearchView,
      },
      {
        path: '/spaces/:spaceId',
        name: 'space-detail',
        component: SpaceDetailView,
      },
      {
        path: '/admin/users',
        name: 'user-admin',
        component: UserAdminView,
        meta: { systemAdmin: true },
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
    if (to.meta.systemAdmin) {
      try {
        if (!(await adminApi.canManageUsers())) return { name: 'knowledge-home' }
      } catch {
        // Let the destination page render the service failure with a retry action.
      }
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
