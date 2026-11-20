<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { spacesApi, type KnowledgeSpace } from '../api/spaces'
import { ApiRequestError } from '../api/http'
import { setQuestionDraft } from '../qaDraft'

const router = useRouter()
const spaces = ref<KnowledgeSpace[]>([])
const question = ref('')
const loading = ref(true)
const error = ref('')
async function load() {
  loading.value = true; error.value = ''
  try { spaces.value = await spacesApi.listSpaces() }
  catch (failure) { error.value = failure instanceof ApiRequestError ? failure.displayMessage : '加载知识空间失败，请重试' }
  finally { loading.value = false }
}
async function startQuestion() {
  setQuestionDraft(question.value.trim())
  await router.push('/qa')
}
onMounted(load)
</script>

<template>
  <section class="workbench">
    <section
      class="question-hero"
      aria-labelledby="ask-title"
    >
      <h2 id="ask-title">
        今天想了解什么？
      </h2>
      <form @submit.prevent="startQuestion">
        <label
          class="sr-only"
          for="workbench-question"
        >输入问题</label><input
          id="workbench-question"
          v-model="question"
          name="question"
          maxlength="4000"
          placeholder="输入问题，例如：项目验收需要准备哪些材料？"
        ><button
          class="primary-action"
          type="submit"
        >
          开始提问 ↗
        </button>
      </form>
    </section>
    <section aria-labelledby="quick-title">
      <div class="section-heading">
        <div>
          <h2 id="quick-title">
            常用操作
          </h2>
        </div>
      </div><div class="quick-grid">
        <RouterLink to="/upload">
          <span>⇧</span><div><strong>上传文档</strong><small>上传资料并提交解析与审核</small></div>
        </RouterLink><RouterLink to="/search">
          <span>⌕</span><div><strong>查找知识</strong><small>搜索有权访问的文档与片段</small></div>
        </RouterLink><RouterLink to="/solutions">
          <span>▤</span><div><strong>方案工作台</strong><small>基于知识资产整理方案</small></div>
        </RouterLink>
      </div>
    </section>
    <section aria-labelledby="spaces-title">
      <div class="section-heading">
        <div>
          <h2 id="spaces-title">
            我的知识空间
          </h2>
        </div><RouterLink
          class="all-link"
          to="/"
        >
          查看全部 →
        </RouterLink>
      </div>
      <div
        v-if="loading"
        class="state"
        role="status"
      >
        正在加载知识空间…
      </div><div
        v-else-if="error"
        class="state state-error"
        role="alert"
      >
        {{ error }} <button
          type="button"
          @click="load"
        >
          重试
        </button>
      </div><div
        v-else-if="spaces.length === 0"
        class="state"
      >
        暂无可访问的知识空间，请联系管理员。
      </div>
      <div
        v-else
        class="space-grid"
      >
        <RouterLink
          v-for="space in spaces.slice(0, 6)"
          :key="space.id"
          class="space-card"
          :to="`/spaces/${encodeURIComponent(space.id)}`"
        >
          <span class="card-icon">▦</span><strong :title="space.name">{{ space.name }}</strong><small :title="space.description">{{ space.description || '暂无说明' }}</small><span class="card-link">进入空间 →</span>
        </RouterLink>
      </div>
    </section>
  </section>
</template>

<style scoped>
.workbench{display:grid;gap:32px;min-width:0}.workbench header h1{margin:6px 0 8px;font-size:clamp(26px,3vw,36px)}.workbench p{margin:0;color:var(--color-muted);line-height:1.6}.eyebrow{color:#315eea!important;font-size:12px;font-weight:800;letter-spacing:.08em}.question-hero{padding:clamp(22px,3vw,32px);border-radius:16px;background:linear-gradient(116deg,#132851,#1d3f79 68%,#394398);color:#fff}.question-hero h2{margin:0 0 8px;font-size:20px}.question-hero p{color:#d4e2ff}.question-hero form{display:flex;gap:10px;max-width:650px;margin-top:18px}.question-hero input{min-width:0;flex:1;padding:12px 14px;border:1px solid #b5c6e4;border-radius:9px;background:#fff;color:#14213a}.primary-action{min-height:44px;padding:0 18px;border:1px solid #315eea;border-radius:9px;background:#315eea;color:white;-webkit-text-fill-color:white;appearance:none;font-weight:700;white-space:nowrap;cursor:pointer}.primary-action:hover{background:#244ac7}.primary-action:disabled{opacity:.55;cursor:not-allowed}.workbench :is(button,a,input):focus-visible{outline:3px solid #8cc4ff;outline-offset:2px}.section-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:15px}.section-heading h2{margin:0 0 5px;font-size:18px}.section-heading p{font-size:12px}.all-link,.card-link{color:#315eea;font-size:13px;font-weight:700}.quick-grid,.space-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.quick-grid a,.space-card{min-width:0;border:1px solid #e0e8f6;border-radius:13px;background:white;box-shadow:var(--shadow-panel);text-decoration:none;color:var(--color-ink)}.quick-grid a{display:flex;gap:13px;padding:19px}.quick-grid a>span,.card-icon{display:grid;flex:none;width:38px;height:38px;place-items:center;border-radius:10px;background:#eaf0ff;color:#315eea}.quick-grid strong,.quick-grid small,.space-card strong,.space-card small{display:block;min-width:0;overflow-wrap:anywhere}.quick-grid strong{margin-bottom:5px;font-size:14px}.quick-grid small,.space-card small{color:var(--color-muted);line-height:1.5}.space-card{display:grid;gap:12px;padding:18px}.space-card strong{font-size:15px}.state{padding:24px;border:1px solid var(--color-border);border-radius:12px;background:white;color:var(--color-muted)}.state-error{color:#982f3f}.state button{margin-left:10px;padding:5px 10px;border:1px solid var(--color-border);border-radius:6px;background:#fff}.sr-only{position:absolute;width:1px;height:1px;padding:0;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}@media(max-width:1000px){.quick-grid,.space-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){.quick-grid,.space-grid{grid-template-columns:1fr}.question-hero form{flex-direction:column}.primary-action{width:100%}}
</style>
