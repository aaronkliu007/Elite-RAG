import { createRouter, createWebHistory, type RouterHistory } from 'vue-router'

import { adminApi } from '../api/admin'
import { useSessionStore } from '../stores/session'
import DocumentDetailView from '../views/DocumentDetailView.vue'
import KnowledgeHomeView from '../views/KnowledgeHomeView.vue'
import LoginView from '../views/LoginView.vue'
import QaView from '../views/QaView.vue'
import QaEvaluationView from '../views/QaEvaluationView.vue'
import SearchView from '../views/SearchView.vue'
import SolutionListView from '../views/SolutionListView.vue'
import SolutionDetailView from '../views/SolutionDetailView.vue'
import SpaceDetailView from '../views/SpaceDetailView.vue'
import UploadView from '../views/UploadView.vue'
import WorkbenchView from '../views/WorkbenchView.vue'
import UserAdminView from '../views/UserAdminView.vue'
import ChangePasswordView from '../views/ChangePasswordView.vue'
import PermissionAdminView from '../views/PermissionAdminView.vue'

export function createAppRouter(history: RouterHistory = createWebHistory()) {
  const router = createRouter({
    history,
    routes: [
      {
        path: '/change-password',
        name: 'change-password',
        component: ChangePasswordView,
      },
      {
        path: '/login',
        name: 'login',
        component: LoginView,
        meta: { public: true },
      },
      {
        path: '/workbench',
        name: 'workbench',
        component: WorkbenchView,
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
        path: '/qa',
        name: 'knowledge-qa',
        component: QaView,
      },
      {
        path: '/solutions',
        name: 'solution-home',
        component: SolutionListView,
      },
      {
        path: '/spaces/:spaceId/solutions',
        name: 'solution-list',
        component: SolutionListView,
      },
      {
        path: '/solutions/:solutionId',
        name: 'solution-detail',
        component: SolutionDetailView,
      },
      {
        path: '/spaces/:spaceId',
        name: 'space-detail',
        component: SpaceDetailView,
      },
      {
        path: '/admin/evaluations',
        name: 'qa-evaluations',
        component: QaEvaluationView,
        meta: { systemAdmin: true },
      },
      {
        path: '/admin/users',
        name: 'user-admin',
        component: UserAdminView,
        meta: { systemAdmin: true },
      },
      {
        path: '/admin/permissions',
        name: 'permission-admin',
        component: PermissionAdminView,
      },
    ],
  })

  router.beforeEach(async (to) => {
    const session = useSessionStore()
    await session.bootstrap()
    if (to.meta.public) {
      if (to.name === 'login' && session.authenticated) {
        return safeRedirect(to.query.redirect) ?? { name: 'workbench' }
      }
      return true
    }
    if (!session.authenticated) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    if (session.passwordChangeRequired && to.name !== 'change-password') return { name: 'change-password' }
    if (!session.passwordChangeRequired && to.name === 'change-password') return { name: 'workbench' }
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
