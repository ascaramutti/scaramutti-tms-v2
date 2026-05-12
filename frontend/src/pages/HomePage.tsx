import { LogOut, Truck } from 'lucide-react'
import { useAuth } from '../shared/auth/AuthContext'

export function HomePage() {
  const { user, clearSession } = useAuth()

  // clearSession() actualiza el contexto → ProtectedRoute detecta !isAuthenticated
  // y redirige a /login automaticamente. No hace falta llamar navigate aca.
  const handleLogout = () => {
    clearSession()
  }

  return (
    <div className="min-h-screen flex flex-col bg-slate-50">
      {/* Header */}
      <header className="bg-white border-b border-slate-200">
        <div className="mx-auto max-w-5xl px-6 py-3 flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="bg-blue-600 p-1.5 rounded-lg">
              <Truck className="w-5 h-5 text-white" aria-hidden="true" />
            </div>
            <span className="font-semibold text-slate-900">Scaramutti TMS</span>
          </div>

          <div className="flex items-center gap-4">
            <div className="text-right">
              <p className="text-sm font-medium text-slate-900 leading-tight">
                {user?.fullName ?? '—'}
              </p>
              {user?.position && (
                <p className="text-xs text-slate-500 leading-tight">{user.position}</p>
              )}
            </div>
            <button
              type="button"
              onClick={handleLogout}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 shadow-sm hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 transition-colors"
            >
              <LogOut className="w-4 h-4" aria-hidden="true" />
              Cerrar sesión
            </button>
          </div>
        </div>
      </header>

      {/* Body */}
      <main className="flex-1 mx-auto w-full max-w-5xl px-6 py-12">
        <div className="bg-white rounded-2xl ring-1 ring-slate-200 p-8 text-center">
          <h1 className="text-2xl font-semibold text-slate-900">
            Bienvenido, {user?.fullName?.split(' ')[0] ?? 'usuario'}
          </h1>
          <p className="mt-2 text-sm text-slate-500">
            Rol activo: <span className="font-medium text-slate-700">{user?.role}</span>
          </p>
          <p className="mt-6 text-sm text-slate-400">
            Las pantallas del sistema están en construcción. Próximamente podrás
            gestionar cotizaciones, clientes y servicios desde acá.
          </p>
        </div>
      </main>
    </div>
  )
}
