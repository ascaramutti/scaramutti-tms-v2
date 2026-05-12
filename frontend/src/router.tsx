import { createBrowserRouter } from 'react-router-dom'

// Router placeholder. Las pantallas reales se registran cuando cada feature
// se implementa (ej. /new-screen /login agrega su ruta).
export const router = createBrowserRouter([
  {
    path: '/',
    element: (
      <main style={{ padding: '2rem' }}>
        <h1>Scaramutti TMS</h1>
        <p>Frontend bootstrap activo. Pantallas pendientes de implementar.</p>
      </main>
    ),
  },
])
