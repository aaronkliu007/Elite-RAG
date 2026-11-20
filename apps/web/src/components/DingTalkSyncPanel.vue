<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { dingtalkApi, type DingTalkDepartment, type DingTalkStatus, type DingTalkSummary } from '../api/dingtalk'
const emit = defineEmits<{ synced: [] }>()
const status = ref<DingTalkStatus>()
const summary = ref<DingTalkSummary>()
const busy = ref(false)
const error = ref('')
const success = ref(false)
const showDepartments = ref(false)
const departments = ref<Array<DingTalkDepartment & { depth: number }>>([])
async function toggleDepartments() {
  if (showDepartments.value) { showDepartments.value = false; return }
  error.value = ''
  try {
    const items = await dingtalkApi.departments()
    const output: Array<DingTalkDepartment & { depth: number }> = []
    const seen = new Set<string>()
    function visit(parentId: string | null, depth: number) {
      for (const item of items.filter(candidate => candidate.parentId === parentId)) {
        if (seen.has(item.id)) continue
        seen.add(item.id); output.push({ ...item, depth }); visit(item.id, depth + 1)
      }
    }
    visit(null, 0)
    if (seen.size !== items.length) throw new Error('组织结构不完整')
    departments.value = output; showDepartments.value = true
  } catch { error.value = '无法加载钉钉组织架构。' }
}
async function load() {
  try { status.value = await dingtalkApi.status() } catch { error.value = '无法获取钉钉接入状态。' }
}
async function preview() {
  busy.value = true; error.value = ''; success.value = false; summary.value = undefined
  try { summary.value = await dingtalkApi.preview() } catch { error.value = '读取钉钉通讯录失败，请检查配置与授权范围。' }
  finally { busy.value = false }
}
async function sync() {
  if (!summary.value || !status.value?.authoritativeScopeConfirmed) return
  busy.value = true; error.value = ''; success.value = false
  try { summary.value = await dingtalkApi.sync(); success.value = true; showDepartments.value = false; emit('synced') }
  catch { error.value = '无法确认同步结果，请刷新用户列表核实后重试。' }
  finally { busy.value = false }
}
onMounted(load)
</script>
<template>
  <section
    class="ding-sync"
    aria-label="钉钉通讯录同步"
  >
    <strong>钉钉通讯录</strong>
    <span v-if="!status && !error">正在检查…</span>
    <span v-else-if="status && !status.configured">钉钉尚未配置</span>
    <template v-else-if="status?.configured">
      <span v-if="summary">{{ summary.departmentCount }} 个部门、{{ summary.userCount }} 位成员{{ success ? ' · 同步完成' : ' · 预览结果' }}</span>
      <span v-else>已配置</span>
      <button
        type="button"
        :aria-expanded="showDepartments"
        @click="toggleDepartments"
      >
        {{ showDepartments ? '收起组织架构' : '查看组织架构' }}
      </button>
      <button
        data-action="dingtalk-preview"
        type="button"
        :disabled="busy"
        @click="preview"
      >
        预览通讯录
      </button>
      <button
        data-action="dingtalk-sync"
        type="button"
        :disabled="busy || !summary || !status.authoritativeScopeConfirmed"
        @click="sync"
      >
        同步通讯录
      </button>
      <span v-if="!status.authoritativeScopeConfirmed">需先确认通讯录覆盖范围</span>
    </template>
    <span
      v-if="error"
      role="alert"
    >{{ error }}</span>
    <ul
      v-if="showDepartments"
      class="departments"
      aria-label="钉钉部门列表"
    >
      <li
        v-for="dept in departments"
        :key="dept.id"
        :style="{ paddingLeft: `${Math.min(dept.depth, 8) * 16}px` }"
      >
        {{ dept.name }}
      </li>
      <li v-if="departments.length === 0">
        尚未同步部门。
      </li>
    </ul>
  </section>
</template>
<style scoped>
.ding-sync { display:flex; align-items:center; flex-wrap:wrap; gap:10px; padding:12px 18px; border:1px solid var(--color-border); border-radius:var(--radius-panel); background:#fff; color:var(--color-muted); font-size:13px; }
.ding-sync strong { color:var(--color-ink); } button { padding:7px 12px; border:1px solid var(--color-border); border-radius:var(--radius-control); background:#fff; color:var(--color-primary); cursor:pointer; } button:disabled { opacity:.5; cursor:not-allowed; } button:focus-visible { outline:3px solid var(--color-focus); outline-offset:2px; }
.departments { flex-basis:100%; list-style:none; margin:0; padding:0; line-height:1.9; color:var(--color-ink); }
</style>
