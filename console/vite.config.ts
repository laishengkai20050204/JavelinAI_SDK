// vite.config.ts
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
    plugins: [react()],

    // 这些是顶层配置，不要放到 server 里面
    base: '/console/',
    build: { outDir: 'dist' },

    server: {
        port: 5173,
        proxy: {
            '/audit':    { target: 'http://localhost:8080', changeOrigin: true },
            '/admin': { target: 'http://localhost:8080', changeOrigin: true },
            '/ai':    { target: 'http://localhost:8080', changeOrigin: true },
        },
    },
})
