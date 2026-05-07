import { createApp, h } from 'vue'
import { createPinia } from 'pinia'
import OpsView from '@/views/OpsView.vue'
import AppToast from '@/components/common/AppToast.vue'

import '@/styles/fonts.css'
import '@/styles/base.css'

declare global {
  interface Window {
    __OPS_BOOT_STAGE__?: string
    __OPS_BOOT_ERROR__?: string
  }
}

window.__OPS_BOOT_STAGE__ = 'script_loaded'

try {
  const pinia = createPinia()
  window.__OPS_BOOT_STAGE__ = 'pinia_created'

  const app = createApp({
    render() {
      return h('div', { class: 'ops-app-root' }, [h(OpsView), h(AppToast)])
    },
  })
  window.__OPS_BOOT_STAGE__ = 'app_created'

  app.config.errorHandler = (error) => {
    const message = error instanceof Error
      ? (error.stack || error.message)
      : String(error)
    window.__OPS_BOOT_STAGE__ = 'app_runtime_error'
    window.__OPS_BOOT_ERROR__ = message
    throw error
  }

  app.use(pinia)
  window.__OPS_BOOT_STAGE__ = 'app_used_pinia'

  app.mount('#app')
  window.__OPS_BOOT_STAGE__ = 'app_mounted'
} catch (error) {
  const message = error instanceof Error
    ? (error.stack || error.message)
    : String(error)
  window.__OPS_BOOT_STAGE__ = 'app_boot_failed'
  window.__OPS_BOOT_ERROR__ = message
  throw error
}
