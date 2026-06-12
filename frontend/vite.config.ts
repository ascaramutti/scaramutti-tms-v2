import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  // La app vive bajo /cotizaciones/ (assets + index.html). El routing interno ya
  // usa el mismo prefijo en sus paths, así dev y prod sirven URLs idénticas.
  // Contexto: unificación con v1 detrás de un gateway (ver docs/PLAN_UNIFICACION_SSO.md).
  base: '/cotizaciones/',
  plugins: [react(), tailwindcss()],
})
