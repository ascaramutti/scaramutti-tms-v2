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
    // La zona del proceso se fija, y se fija LEJOS de la de la operacion. Corriendo
    // bajo `America/Lima`, un calculo hecho con la zona del navegador da el mismo
    // resultado que el correcto, asi que un test de fechas no distingue el codigo
    // bueno del malo y pasa por el motivo equivocado. Tokio es UTC+9, del signo
    // opuesto a Lima y a catorce horas: en los instantes que estos tests usan, las
    // dos zonas estan en dias distintos.
    //
    // Se midio antes de fijarla: la suite entera pasa con `TZ=Asia/Tokyo`. Ojo con
    // lo que ese verde significa y lo que no: prueba que los tests de hoy no
    // dependen de la zona, no que todo helper de fechas del sistema sea correcto
    // fuera de Lima. Lo que gana el cambio es que de aca en adelante cualquier test
    // de fecha se mide contra una zona que no es la de la operacion.
    //
    // `FORCE_TZ` es la vía de escape. Sin ella, fijar la zona acá haría que
    // `TZ=America/Lima npm test` ya no corriera en la zona de la operación, en silencio
    // y justo el día que haga falta reproducir un problema de fechas como lo ve la
    // oficina. Se comprobó que el valor de acá pisa al del entorno en los workers.
    env: { TZ: process.env.FORCE_TZ ?? 'Asia/Tokyo' },
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/api/**',                       // autogenerado
        'src/test/**',                      // setup/mocks
        '**/*.test.{ts,tsx}',               // tests
        'src/main.tsx',                     // entrypoint, dificil de testear sin browser
        // Wiring/config sin logica de negocio.
        // `router.tsx` se excluye aunque SI tenga test (`router.test.tsx` monta la
        // tabla real): su cobertura seria 100% con solo importarlo, porque la tabla
        // se evalua entera al cargar el modulo. Un 100% que no distingue una ruta
        // probada de una que nadie visito engaña mas que ayudar; lo que da la
        // garantia es el test, no el numero.
        'src/router.tsx',                   // tabla declarativa de rutas
        'src/shared/query/queryClient.ts',  // defaults de react-query
        'src/pages/HomePage.tsx',           // placeholder pre-design final
      ],
    },
  },
})
