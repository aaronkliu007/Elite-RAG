<script setup lang="ts">
defineProps<{ permissionScope: string; canManageAcl: boolean }>()
defineEmits<{ manage: [] }>()
</script>

<template>
  <section
    class="acl-panel"
    aria-labelledby="acl-title"
  >
    <div>
      <p class="eyebrow">
        默认拒绝
      </p>
      <h3 id="acl-title">
        权限范围
      </h3>
      <p :title="permissionScope">
        {{ permissionScope || '尚未配置可见范围' }}
      </p>
    </div>
    <button
      v-if="canManageAcl"
      class="button-secondary"
      type="button"
      @click="$emit('manage')"
    >
      调整权限范围
    </button>
  </section>
</template>

<style scoped>
.acl-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  min-width: 0;
  padding: 20px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-panel);
  background: #fff;
  box-shadow: var(--shadow-panel);
}

.acl-panel > div { min-width: 0; }
.eyebrow { margin: 0 0 4px; color: var(--color-accent); font-size: 11px; font-weight: 800; letter-spacing: 0.1em; }
h3 { margin: 0 0 6px; }
p:last-child { max-width: 720px; margin: 0; overflow: hidden; color: var(--color-muted); text-overflow: ellipsis; white-space: nowrap; }

.button-secondary {
  flex: 0 0 auto;
  min-height: 42px;
  padding: 0 15px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  color: var(--color-ink);
  background: #fff;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
  appearance: none;
}

.button-secondary:hover { border-color: var(--color-accent); color: var(--color-accent); }
.button-secondary:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }

@media (max-width: 720px) {
  .acl-panel { align-items: stretch; flex-direction: column; }
}
</style>
