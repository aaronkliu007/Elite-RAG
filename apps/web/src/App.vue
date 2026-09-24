<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'

import { useSessionStore } from './stores/session'

const session = useSessionStore()
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink
        class="brand"
        to="/"
      >
        <span class="brand-mark">云</span>
        <span><small>YUNECT · KNOWLEDGE</small><strong>企业知识库</strong></span>
      </RouterLink>
      <nav aria-label="主导航">
        <RouterLink to="/">
          知识空间
        </RouterLink>
        <RouterLink to="/upload">
          安全上传
        </RouterLink>
      </nav>
      <div
        class="session-chip"
        title="钉钉组织身份"
      >
        <span class="session-avatar">企</span>
        <span>
          <strong>{{ session.user?.name ?? '内网用户' }}</strong>
          <small>{{ session.user?.departmentName ?? '钉钉组织同步' }}</small>
        </span>
      </div>
    </header>
    <main><RouterView /></main>
  </div>
</template>

<style>
:root {
  color: #142238;
  background: #f2f5f8;
  font-family: Inter, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-synthesis: none;
  --color-ink: #142238;
  --color-muted: #627083;
  --color-primary: #124c63;
  --color-primary-strong: #0b3749;
  --color-accent: #167486;
  --color-focus: rgb(39 162 183 / 32%);
  --color-border: #d7e0e8;
  --color-border-soft: #e8edf2;
  --radius-panel: 16px;
  --radius-control: 9px;
  --shadow-panel: 0 16px 40px rgb(26 54 75 / 7%);
  --font-mono: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
}

* { box-sizing: border-box; }
body { margin: 0; min-width: 320px; min-height: 100vh; color: var(--color-ink); background: radial-gradient(circle at 15% 0%, rgb(61 145 157 / 10%), transparent 34%), #f2f5f8; }
button, input, select { font: inherit; }
.app-shell { min-height: 100vh; }
.app-header { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 38px; min-height: 78px; padding: 12px clamp(20px, 4vw, 56px); border-bottom: 1px solid rgb(255 255 255 / 18%); color: #fff; background: #103f54; box-shadow: 0 8px 30px rgb(7 39 52 / 14%); }
.brand { display: flex; align-items: center; gap: 12px; color: inherit; text-decoration: none; }
.brand-mark { display: grid; width: 42px; height: 42px; border: 1px solid rgb(255 255 255 / 40%); border-radius: 12px; background: #167486; font-size: 21px; font-weight: 850; place-items: center; }
.brand > span:last-child { display: grid; gap: 2px; }
.brand small { font-size: 9px; letter-spacing: 0.14em; opacity: 0.72; }
.brand strong { font-size: 18px; }
nav { display: flex; gap: 6px; }
nav a { padding: 9px 12px; border-radius: 8px; color: rgb(255 255 255 / 76%); font-size: 14px; font-weight: 700; text-decoration: none; }
nav a:hover, nav a.router-link-active { color: #fff; background: rgb(255 255 255 / 11%); }
.brand:focus-visible, nav a:focus-visible { outline: 3px solid rgb(129 224 237 / 55%); outline-offset: 2px; }
.session-chip { display: flex; align-items: center; gap: 9px; min-width: 0; }
.session-avatar { display: grid; width: 34px; height: 34px; flex: 0 0 auto; border-radius: 50%; color: #103f54; background: #d9f1f3; font-size: 13px; font-weight: 850; place-items: center; }
.session-chip > span:last-child { display: grid; }
.session-chip strong { font-size: 13px; }
.session-chip small { font-size: 10px; opacity: 0.7; }
main { width: min(1500px, 100%); margin: 0 auto; padding: 34px clamp(18px, 4vw, 56px) 56px; }
@media (max-width: 820px) { .app-header { grid-template-columns: minmax(0, 1fr) auto; gap: 14px; } nav { grid-column: 1 / -1; grid-row: 2; } .session-chip > span:last-child { display: none; } }
@media (max-width: 520px) { .brand small { display: none; } .brand strong { font-size: 16px; } }
</style>
