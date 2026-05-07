import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  base: '/ui/',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: resolve(process.cwd(), '../java-backend/src/main/resources/static/ui'),
    emptyOutDir: true,
    rollupOptions: {
      input: {
        query: resolve(process.cwd(), 'index.html'),
        ops: resolve(process.cwd(), 'ops.html'),
      },
      output: {
        entryFileNames: 'assets/[name].js',
        chunkFileNames: 'assets/[name].js',
        assetFileNames: (assetInfo) => {
          const name = assetInfo.name || ''
          const ext = name.includes('.') ? name.substring(name.lastIndexOf('.') + 1) : ''
          if (ext === 'css') {
            return 'assets/[name].css'
          }
          return 'assets/[name].[ext]'
        },
      },
    },
  },
})
