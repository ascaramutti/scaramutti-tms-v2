import { createBrowserRouter, Navigate } from 'react-router-dom'
import { ProtectedRoute } from './shared/auth/ProtectedRoute'
import { AppLayout } from './shared/layout/AppLayout'
import { LoginPage } from './features/auth/components/LoginPage'
import { HomePage } from './pages/HomePage'

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    // Layout route: las rutas autenticadas comparten AppLayout (con sidebar).
    element: (
      <ProtectedRoute>
        <AppLayout />
      </ProtectedRoute>
    ),
    children: [
      { path: '/', element: <HomePage /> },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
])
