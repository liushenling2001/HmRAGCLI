import { createApp, Fragment, h } from 'vue'
import { createPinia } from 'pinia'
import QueryView from '@/views/QueryView.vue'
import AppToast from '@/components/common/AppToast.vue'

import '@/styles/fonts.css'
import '@/styles/base.css'

const pinia = createPinia()

const rootApp = createApp({
  render() {
    return h(Fragment, null, [h(QueryView), h(AppToast)])
  },
})
rootApp.use(pinia)
rootApp.mount('#app')
