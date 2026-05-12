import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Vite 8 (rolldown) y Vitest 3 traen tipos de Plugin incompatibles entre si.
// Mantenemos vite.config.ts limpio para el build y declaramos aca el setup
// de Vitest. El cast `as never` evita el conflicto sin tocar el build.
export default defineConfig({
  plugins: [react() as never],
  test: {
    globals: true,
    environment: 'happy-dom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
