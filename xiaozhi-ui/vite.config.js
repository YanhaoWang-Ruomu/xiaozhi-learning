import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
    plugins: [vue()],
    server: {
        host: '127.0.0.1',
        port: 5173,
        strictPort: true,
        proxy: {
            '/api': {
                target: 'http://127.0.0.1:8081',
                changeOrigin: true,
                timeout: 210000,
                proxyTimeout: 210000,
            },
        },
    },
    resolve: {
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url)),
        },
    },
})