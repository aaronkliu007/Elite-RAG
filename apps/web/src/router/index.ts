import { createRouter, createWebHistory } from 'vue-router'

import KnowledgeHomeView from '../views/KnowledgeHomeView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'knowledge-home',
      component: KnowledgeHomeView,
    },
  ],
})
