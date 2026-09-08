import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  // La app se sirve desde la raíz del dominio. Hasta la mudanza de 2026-09 vivía
  // bajo /cotizaciones, el prefijo con el que convivía con la v1 detrás de un
  // gateway; retirada la v1, ese prefijo dejó de tener sentido.
  base: '/',
  plugins: [react(), tailwindcss()],
})
