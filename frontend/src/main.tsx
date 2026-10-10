import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import './index.css'
import { router } from './router'
import { queryClient } from './shared/query/queryClient'
import { configureSessionHttpClient } from './shared/http/client'
import { AuthProvider } from './shared/auth/AuthContext'
import { ThemeProvider } from './shared/ui/theme/ThemeContext'
import { ThemedToaster } from './shared/ui/theme/ThemedToaster'

configureSessionHttpClient(queryClient)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <AuthProvider>
          <RouterProvider router={router} />
          <ThemedToaster />
        </AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>
  </StrictMode>,
)
