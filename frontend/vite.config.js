import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  server: {
    proxy: {
      '/ws': {
        target: 'ws://localhost:17172',
        ws: true,
        changeOrigin: true,
      },
    },
  },
})
