import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useToastStore = defineStore('toast', () => {
  const message = ref('')
  const visible = ref(false)
  let timer: ReturnType<typeof setTimeout> | null = null

  const show = (text: string, duration = 2500) => {
    message.value = text
    visible.value = true
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      visible.value = false
      timer = null
    }, duration)
  }

  const hide = () => {
    visible.value = false
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  return {
    message,
    visible,
    show,
    hide,
  }
})