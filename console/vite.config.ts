import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
    plugins: [react()],
    server: {
        port: 5173,
        proxy: {
            '/admin': { target: 'http://localhost:8080', changeOrigin: true },
            // '/ai': { target: 'http://localhost:8080', changeOrigin: true }, // 回放页后续可用
        },
    },
    base: '/console/',
    build: { outDir: 'dist' },
})
