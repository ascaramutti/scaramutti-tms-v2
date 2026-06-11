import { useAuth } from '../shared/auth/AuthContext'

/**
 * Página de inicio (placeholder).
 * El shell (AppLayout) provee el sidebar con datos del usuario y logout.
 * Acá solo va el contenido del cuerpo.
 */
export function HomePage() {
  const { user } = useAuth()
  const firstName = user?.fullName?.split(' ')[0] ?? 'usuario'

  return (
    <div className="mx-auto max-w-5xl px-6 py-12">
      <div className="bg-white rounded-2xl ring-1 ring-slate-200 p-8 text-center">
        <h1 className="text-2xl font-semibold text-slate-900">
          Bienvenido, {firstName}
        </h1>
        <p className="mt-2 text-sm text-slate-500">
          Rol activo: <span className="font-medium text-slate-700">{user?.role}</span>
        </p>
        <p className="mt-6 text-sm text-slate-400">
          Las pantallas del sistema están en construcción. Próximamente podrás
          gestionar cotizaciones, clientes y servicios desde acá.
        </p>
      </div>
    </div>
  )
}
