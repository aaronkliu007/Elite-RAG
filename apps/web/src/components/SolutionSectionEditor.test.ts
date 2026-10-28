import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'

import SolutionSectionEditor from './SolutionSectionEditor.vue'

const at = '2026-10-29T04:00:00Z'
const baseline = { id: 'adopted-1', kind: 'ADOPTED', content: '原始方案',
  parentVersionId: 'draft-1', baselineVersionId: null, createdBy: 'editor-1', createdAt: at,
  claims: [], verification: { verifications: [] } }
const edited = { ...baseline, id: 'edit-1', kind: 'HUMAN_EDIT', content: '人工修改后的方案',
  parentVersionId: 'adopted-1', baselineVersionId: 'adopted-1',
  verification: { verifications: [{ claimId: 'claim-1', verdict: 'HUMAN_CONFIRMATION_REQUIRED',
    reasonCode: 'HUMAN_EDIT_REVIEW_REQUIRED' }] } }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
})

beforeEach(() => vi.restoreAllMocks())

it('修订先保存候选，版本并排对比，人工核对后才采纳', async () => {
  const posts: { path: string, body: Record<string, unknown> }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    if (path.endsWith('/session/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf', parameterName: '_csrf' })
    if (init?.method === 'POST') {
      posts.push({ path, body: JSON.parse(String(init.body)) as Record<string, unknown> })
      return path.endsWith('/adopt') ? json({ solutionId: 'solution-1', revision: 8 }) : json(edited)
    }
    if (path.endsWith('/versions')) return json(posts.length ? [edited, baseline] : [baseline])
    if (path.includes('/edits/latest?')) return json({ version: null })
    if (path.endsWith('/versions/adopted-1')) return json(baseline)
    if (path.endsWith('/versions/edit-1')) return json(edited)
    throw new Error(path)
  }))
  const wrapper = mount(SolutionSectionEditor, { props: {
    solutionId: 'solution-1', sectionId: 'section-1', revision: 7,
    baselineVersionId: 'adopted-1', canEdit: true, disabled: false,
  } })
  await wrapper.get('[data-action="open-section-editor"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-action="section-edit-content"]').setValue('人工修改后的方案')
  await wrapper.get('[data-action="save-section-edit"]').trigger('click')
  await flushPromises()
  expect(posts[0]?.body).toEqual({ baselineVersionId: 'adopted-1',
    expectedDraftVersionId: null, expectedRevision: 7, content: '人工修改后的方案' })
  expect(wrapper.text()).toContain('待人工确认')
  expect(wrapper.get('[data-action="adopt-section-edit"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[data-action="section-edit-content"]').setValue('尚未保存的新修改')
  await wrapper.get('[data-action="review-section-edit"]').setValue(true)
  expect(wrapper.get('[data-action="adopt-section-edit"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[data-action="section-edit-content"]').setValue('人工修改后的方案')
  await wrapper.get('[data-action="review-section-edit"]').setValue(true)
  await wrapper.get('[data-action="adopt-section-edit"]').trigger('click')
  await flushPromises()
  expect(posts[1]?.body).toEqual({ expectedRevision: 7 })
  expect(wrapper.emitted('adopted')).toHaveLength(1)
  wrapper.unmount()
})

it('冲突后保留本地正文并展示服务器正文', async () => {
  let historyReads = 0
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    if (path.endsWith('/session/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf', parameterName: '_csrf' })
    if (init?.method === 'POST') return json({ code: 'SOLUTION_CONCURRENT_UPDATE',
      message: '版本已变化', traceId: 'trace', fieldErrors: {} }, 409)
    if (path.endsWith('/versions')) return json(++historyReads > 1 ? [edited, baseline] : [baseline])
    if (path.includes('/edits/latest?')) return json({ version: historyReads > 1 ? edited : null })
    if (path.endsWith('/versions/adopted-1')) return json(baseline)
    if (path.endsWith('/versions/edit-1')) return json(edited)
    throw new Error(path)
  }))
  const wrapper = mount(SolutionSectionEditor, { props: {
    solutionId: 'solution-1', sectionId: 'section-1', revision: 7,
    baselineVersionId: 'adopted-1', canEdit: true, disabled: false,
  } })
  await wrapper.get('[data-action="open-section-editor"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-action="section-edit-content"]').setValue('我的未保存正文')
  await wrapper.get('[data-action="save-section-edit"]').trigger('click')
  await flushPromises()
  expect((wrapper.get('[data-action="section-edit-content"]').element as HTMLTextAreaElement).value)
    .toBe('我的未保存正文')
  expect(wrapper.get('[data-action="server-conflict-content"]').text()).toContain('人工修改后的方案')
  expect(wrapper.emitted('reload')).toBeUndefined()
  wrapper.unmount()
})
