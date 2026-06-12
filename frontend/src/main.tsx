import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import { Toaster } from 'sonner'
import './index.css'
import { router } from './router'
import { queryClient } from './shared/query/queryClient'
import { configureHttpClient } from './shared/http/client'
import { AuthProvider } from './shared/auth/AuthContext'
import { currentUserQueryKey } from './shared/auth/queryKeys'

configureHttpClient(() => {
  // Cuando el refresh falla, limpiar el cache de currentUser y redirigir al login.
  // El setQueryData(null) es necesario para el caso edge de que ya estemos en
  // /login (sin recarga via window.location.assign): el AuthContext queda
  // sincronizado y muestra el form en vez de hacer flicker con datos viejos.
  queryClient.setQueryData(currentUserQueryKey, null)
  if (window.location.pathname !== '/cotizaciones/login') {
    window.location.assign('/cotizaciones/login')
  }
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
        <Toaster richColors position="top-right" />
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
)
