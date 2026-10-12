/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';
import { defineConfig, type ProxyOptions } from 'vite';
import react from '@vitejs/plugin-react';

// The live API (nginx in front of the Spring backend and the FastAPI analytics service).
const API_TARGET = process.env.PGMS_API_TARGET ?? 'http://localhost:58088';

/**
 * Same-origin proxy for local development. The browser talks to the Vite origin only, exactly like production
 * where the SPA, /api and /analytics share one nginx origin.
 *
 * Spring's CORS filter rejects requests whose Origin header is not in the allow-list (it would answer 403
 * "Invalid CORS request"), so we rewrite Origin to the target: from the backend's point of view the request
 * is same-origin, which is what it is in production.
 */
const proxy: ProxyOptions = {
  target: API_TARGET,
  changeOrigin: true,
  configure: (p) => {
    p.on('proxyReq', (proxyReq) => {
      if (proxyReq.getHeader('origin')) proxyReq.setHeader('origin', API_TARGET);
      proxyReq.removeHeader('referer');
    });
  },
};

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 55173,
    strictPort: true,
    host: '127.0.0.1',
    proxy: { '/api': proxy, '/analytics': proxy },
  },
  preview: {
    port: 55174,
    strictPort: true,
    host: '127.0.0.1',
    proxy: { '/api': proxy, '/analytics': proxy },
  },
  build: {
    target: 'es2022',
    sourcemap: false,
    chunkSizeWarningLimit: 700,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          query: ['@tanstack/react-query'],
          forms: ['react-hook-form', 'zod', '@hookform/resolvers'],
          charts: ['recharts'],
        },
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
  },
});
