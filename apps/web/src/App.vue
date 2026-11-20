<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'

import { adminApi } from './api/admin'
import { useSessionStore } from './stores/session'

const session = useSessionStore()
const router = useRouter()
const route = useRoute()
const menuOpen = ref(false)
const mobile = ref(false)
const menuButton = ref<HTMLButtonElement | null>(null)
const adminState = ref<'loading' | 'allowed' | 'denied' | 'unavailable'>('loading')
const userName = computed(() => session.user?.displayName || session.user?.username || '')
let capabilityGeneration = 0
let media: MediaQueryList | undefined

async function loadAdminCapability() {
  const generation = ++capabilityGeneration
  const userId = session.user?.userId
  if (!userId || session.passwordChangeRequired) { adminState.value = 'denied'; return }
  adminState.value = 'loading'
  try {
    const allowed = await adminApi.canManageUsers()
    if (generation === capabilityGeneration && session.user?.userId === userId) {
      adminState.value = allowed ? 'allowed' : 'denied'
    }
  } catch {
    if (generation === capabilityGeneration && session.user?.userId === userId) adminState.value = 'unavailable'
  }
}
watch(() => session.user?.userId, () => { void loadAdminCapability() }, { immediate: true })
watch(() => route.fullPath, () => { menuOpen.value = false })
function updateMobile() { mobile.value = Boolean(media?.matches); if (!mobile.value) menuOpen.value = false }
onMounted(() => {
  if (typeof window.matchMedia === 'function') {
    media = window.matchMedia('(max-width: 760px)')
    updateMobile()
    media.addEventListener('change', updateMobile)
  }
})
onBeforeUnmount(() => { capabilityGeneration++; media?.removeEventListener('change', updateMobile) })
function closeMenu() { if (!menuOpen.value) return; menuOpen.value = false; menuButton.value?.focus() }
async function logout() {
  try { await session.logout() } finally { await router.replace('/login') }
}
</script>

<template>
  <div
    class="app-shell"
    :class="{ 'authenticated-shell': session.authenticated && !session.passwordChangeRequired }"
    @keydown.esc="closeMenu"
  >
    <template v-if="session.authenticated && !session.passwordChangeRequired">
      <button
        v-if="mobile && menuOpen"
        class="navigation-backdrop"
        aria-label="关闭导航"
        type="button"
        @click="closeMenu"
      />
      <aside
        id="primary-navigation"
        class="app-sidebar"
        :class="{ 'is-open': menuOpen }"
        :inert="mobile && !menuOpen"
      >
        <RouterLink
          class="brand"
          to="/workbench"
        >
          <span class="brand-mark">云</span>
          <span class="brand-copy"><strong>企业知识库</strong><small>YUNECT KNOWLEDGE</small></span>
        </RouterLink>
        <nav
          class="app-nav"
          aria-label="主导航"
        >
          <p class="nav-label">
            知识工作
          </p>
          <RouterLink to="/workbench">
            <span aria-hidden="true">⌂</span><span>工作台</span>
          </RouterLink>
          <RouterLink
            to="/"
            :class="{ 'current-section': route.path === '/' || /^\/spaces\/[^/]+\/?$/.test(route.path) || route.path.startsWith('/documents/') }"
          >
            <span aria-hidden="true">▦</span><span>知识空间</span>
          </RouterLink>
          <RouterLink to="/upload">
            <span aria-hidden="true">⇧</span><span>上传文档</span>
          </RouterLink>
          <RouterLink to="/search">
            <span aria-hidden="true">⌕</span><span>知识检索</span>
          </RouterLink>
          <RouterLink to="/qa">
            <span aria-hidden="true">✧</span><span>知识问答</span>
          </RouterLink>
          <RouterLink
            to="/solutions"
            :class="{ 'current-section': route.path.startsWith('/solutions') || /^\/spaces\/[^/]+\/solutions(?:\/|$)/.test(route.path) }"
          >
            <span aria-hidden="true">▤</span><span>方案工作台</span>
          </RouterLink>
          <template v-if="adminState === 'allowed'">
            <p class="nav-label">
              系统管理
            </p>
            <RouterLink to="/admin/users">
              <span aria-hidden="true">♙</span><span>用户管理</span>
            </RouterLink>
            <RouterLink to="/admin/permissions">
              <span aria-hidden="true">◇</span><span>权限管理</span>
            </RouterLink>
            <RouterLink to="/admin/evaluations">
              <span aria-hidden="true">◫</span><span>问答评测</span>
            </RouterLink>
          </template>
          <RouterLink
            v-if="adminState === 'denied'"
            to="/admin/permissions"
          >
            <span aria-hidden="true">◇</span><span>权限管理</span>
          </RouterLink>
          <div
            v-else-if="adminState === 'unavailable'"
            class="capability-error"
            role="status"
          >
            管理权限暂时无法确认
            <button
              type="button"
              data-action="retry-admin-capability"
              @click="loadAdminCapability"
            >
              重试
            </button>
          </div>
        </nav>
        <p class="sidebar-note">
          组织知识沉淀与复用
        </p>
      </aside>
      <div class="app-content">
        <header class="app-header">
          <button
            ref="menuButton"
            class="menu-toggle"
            type="button"
            aria-controls="primary-navigation"
            :aria-expanded="menuOpen"
            aria-label="打开导航"
            @click="menuOpen = !menuOpen"
          >
            ☰
          </button>
          <span class="header-title">企业知识库</span>
          <div class="session-actions">
            <span
              class="session-avatar"
              aria-hidden="true"
            >{{ userName.slice(0, 1) }}</span>
            <div class="session-chip">
              <strong :title="userName">{{ userName }}</strong><small :title="session.user?.username">{{ session.user?.username }}</small>
            </div>
            <button
              class="logout-action"
              data-action="logout"
              type="button"
              @click="logout"
            >
              退出登录
            </button>
          </div>
        </header>
        <main class="page-main">
          <RouterView v-slot="{ Component }">
            <component
              :is="Component"
              :key="route.name === 'solution-detail' ? String(route.params.solutionId) : undefined"
            />
          </RouterView>
        </main>
      </div>
    </template>
    <main
      v-else
      class="login-main"
    >
      <RouterView />
    </main>
  </div>
</template>

<style>
:root{color:#14213a;background:#f3f6fc;font-family:Inter,'PingFang SC','Microsoft YaHei',sans-serif;font-synthesis:none;--color-ink:#14213a;--color-muted:#62748e;--color-primary:#315eea;--color-primary-strong:#244ac7;--color-accent:#315eea;--color-focus:rgb(64 124 247 / 38%);--color-border:#dce5f2;--color-border-soft:#e9eef6;--radius-panel:14px;--radius-control:9px;--shadow-panel:0 8px 24px rgb(22 43 84 / 5%);--font-mono:'SFMono-Regular',Consolas,'Liberation Mono',monospace}
*{box-sizing:border-box}body{margin:0;min-width:320px;min-height:100vh;color:var(--color-ink);background:#f3f6fc}button,input,select,textarea{font:inherit}.app-shell{min-height:100vh}.authenticated-shell{display:grid;grid-template-columns:232px minmax(0,1fr)}.app-content{min-width:0}.app-sidebar{position:sticky;top:0;height:100vh;z-index:10;display:flex;flex-direction:column;padding:24px 16px;background:#101d38;border-right:1px solid #263653}.brand{display:flex;align-items:center;gap:11px;padding:0 8px 25px;text-decoration:none;color:#fff}.brand-mark{display:grid;width:38px;height:38px;flex:none;place-items:center;border-radius:11px;background:linear-gradient(140deg,#3b82f6,#655bf4);font-size:19px;font-weight:800}.brand-copy{display:grid;gap:3px;min-width:0}.brand-copy strong{font-size:16px}.brand-copy small{color:#a9b9d5;font-size:10px;letter-spacing:.04em}.app-nav{display:grid;align-content:start;gap:3px}.nav-label{margin:15px 10px 7px;color:#a6b6d1;font-size:12px}.app-nav a{display:flex;align-items:center;gap:12px;min-height:42px;padding:10px 12px;border-radius:8px;color:#c2cee4;font-size:14px;text-decoration:none}.app-nav a span:first-child{width:20px;text-align:center;font-size:17px}.app-nav a:hover,.app-nav a.router-link-exact-active,.app-nav a.current-section{background:#274675;color:#fff}.brand:focus-visible,.app-nav a:focus-visible,.menu-toggle:focus-visible,.logout-action:focus-visible,.capability-error button:focus-visible{outline:3px solid #8cc4ff;outline-offset:2px}.capability-error{display:grid;gap:7px;margin:16px 10px;color:#e8dcbc;font-size:12px}.capability-error button{justify-self:start;padding:4px 10px;border:1px solid #9baed0;border-radius:6px;background:transparent;color:white}.sidebar-note{margin:auto 8px 0;padding-top:16px;border-top:1px solid #30415f;color:#9cacc6;font-size:12px}.app-header{display:flex;align-items:center;gap:14px;min-height:68px;padding:10px clamp(18px,3vw,42px);border-bottom:1px solid #e2e9f4;background:#fff}.header-title{font-size:14px;font-weight:700;color:#425574}.session-actions{display:flex;align-items:center;gap:10px;margin-left:auto;min-width:0}.session-avatar{display:grid;flex:none;width:34px;height:34px;place-items:center;border-radius:50%;background:#eaf0ff;color:#315eea;font-weight:700}.session-chip{display:grid;min-width:0;max-width:180px}.session-chip strong,.session-chip small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.session-chip strong{font-size:13px}.session-chip small{font-size:11px;color:var(--color-muted)}.logout-action{min-height:36px;padding:0 12px;border:1px solid #d8e2f2;border-radius:8px;color:#334765;background:#fff;-webkit-text-fill-color:#334765;appearance:none;cursor:pointer}.logout-action:hover{background:#eef3ff}.page-main{width:min(1500px,100%);margin:auto;padding:34px clamp(18px,3vw,42px) 56px}.login-main{width:100%}.menu-toggle{display:none;min-width:40px;min-height:40px;border:1px solid #d9e4f2;border-radius:8px;background:#fff;color:#233e67;font-size:19px}.navigation-backdrop{display:none}@media(max-width:760px){.authenticated-shell{display:block}.app-sidebar{position:fixed;left:0;top:0;width:240px;transform:translateX(-105%);transition:transform .2s;box-shadow:10px 0 30px #102a3330}.app-sidebar.is-open{transform:translateX(0)}.menu-toggle{display:block}.navigation-backdrop{display:block;position:fixed;inset:0;z-index:9;border:0;background:#13213c80}.session-chip{max-width:100px}.session-chip small{display:none}.page-main{padding:24px 18px 48px}}@media(max-width:420px){.header-title{display:none}.session-chip{max-width:90px}}
</style>
