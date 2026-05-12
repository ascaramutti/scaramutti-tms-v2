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
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/api/**',                       // autogenerado
        'src/test/**',                      // setup/mocks
        '**/*.test.{ts,tsx}',               // tests
        'src/main.tsx',                     // entrypoint, dificil de testear sin browser
        // Wiring/config sin logica: se valida con build + smoke browser, no con unit tests.
        'src/router.tsx',                   // tabla declarativa de rutas
        'src/shared/query/queryClient.ts',  // defaults de react-query
        'src/pages/HomePage.tsx',           // placeholder pre-design final
      ],
    },
  },
})
