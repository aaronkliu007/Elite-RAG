import { createRouter, createWebHistory } from 'vue-router'

import DocumentDetailView from '../views/DocumentDetailView.vue'
import KnowledgeHomeView from '../views/KnowledgeHomeView.vue'
import UploadView from '../views/UploadView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
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
