import { useAuth } from '../shared/auth/AuthContext'

export function HomePage() {
  const { user, clearSession } = useAuth()

  // clearSession() actualiza el contexto → ProtectedRoute detecta !isAuthenticated
  // y redirige a /login automaticamente. No hace falta llamar navigate aca.
  const handleLogout = () => {
    clearSession()
  }

  return (
    <main style={{ padding: '2rem', maxWidth: '40rem', margin: '0 auto' }}>
      <h1>Scaramutti TMS</h1>
      <p>
        Bienvenido <strong>{user?.fullName}</strong>
        {user?.position ? ` — ${user.position}` : null}
      </p>
      <p style={{ color: '#666' }}>Rol: {user?.role}</p>
      <button type="button" onClick={handleLogout}>
        Cerrar sesión
      </button>
    </main>
  )
}
