<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <span class="brand-kicker">YUNECT · RAG</span>
      <h1>企业知识库</h1>
    </header>
    <main>
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
:global(*) {
  box-sizing: border-box;
}

:global(body) {
  margin: 0;
  min-width: 320px;
  min-height: 100vh;
  color: #152238;
  background: #f4f7fb;
  font-family: Inter, "PingFang SC", "Microsoft YaHei", sans-serif;
}

.app-shell {
  min-height: 100vh;
}

.app-header {
  padding: 28px 40px 22px;
  color: #fff;
  background: linear-gradient(120deg, #123c69, #176b87);
}

.app-header h1 {
  margin: 6px 0 0;
  font-size: 28px;
}

.brand-kicker {
  font-size: 12px;
  letter-spacing: 0.16em;
  opacity: 0.8;
}

main {
  padding: 32px 40px;
}
</style>
