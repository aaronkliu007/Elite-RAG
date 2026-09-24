import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import App from './App.vue'
import router from './router'

describe('应用骨架', () => {
  it('展示企业知识库名称', async () => {
    await router.push('/')
    await router.isReady()
    const wrapper = mount(App, {
      global: {
        plugins: [router],
      },
    })

    expect(wrapper.text()).toContain('企业知识库')
  })
})
