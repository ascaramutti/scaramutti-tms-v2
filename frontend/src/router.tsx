import { createBrowserRouter, Navigate } from 'react-router-dom'
import { ProtectedRoute } from './shared/auth/ProtectedRoute'
import { LoginPage } from './features/auth/components/LoginPage'
import { HomePage } from './pages/HomePage'

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: (
      <ProtectedRoute>
        <HomePage />
      </ProtectedRoute>
    ),
  },
  { path: '*', element: <Navigate to="/" replace /> },
])
