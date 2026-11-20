import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { spacesApi } from '../api/spaces'
import WorkbenchView from './WorkbenchView.vue'
import { takeQuestionDraft } from '../qaDraft'

afterEach(() => { vi.restoreAllMocks(); takeQuestionDraft() })

it('工作台显示真实有权空间，并在内存中将问题交给问答页', async () => {
  vi.spyOn(spacesApi, 'listSpaces').mockResolvedValue([{ id:'space-1', name:'研发知识', description:'技术材料', revision:1, createdBy:'u', createdAt:'2026-01-01', allowedActions:['VIEW'] }])
  const router=createRouter({ history:createMemoryHistory(), routes:[{path:'/workbench',component:WorkbenchView},{path:'/qa',component:{template:'<div>问答</div>'}},{path:'/',component:{template:'<div>空间</div>'}},{path:'/upload',component:{template:'<div>上传</div>'}},{path:'/search',component:{template:'<div>检索</div>'}},{path:'/solutions',component:{template:'<div>方案</div>'}},{path:'/spaces/:id',component:{template:'<div>详情</div>'}}] })
  await router.push('/workbench'); await router.isReady()
  const wrapper=mount(WorkbenchView,{global:{plugins:[router]}}); await flushPromises()
  expect(wrapper.text()).toContain('研发知识')
  await wrapper.get('input[name="question"]').setValue('项目验收材料？')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/qa')
  expect(router.currentRoute.value.fullPath).not.toContain('项目验收')
  expect(takeQuestionDraft()).toBe('项目验收材料？')
  wrapper.unmount()
})
