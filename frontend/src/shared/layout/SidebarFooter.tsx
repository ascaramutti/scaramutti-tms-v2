import { useId } from 'react'
import { LogOut } from 'lucide-react'
import { useAuth } from '../auth/AuthContext'

/**
 * Footer del sidebar con info de sesión + botón de logout.
 * clearSession() actualiza el AuthContext → ProtectedRoute redirige a /login.
 */
export function SidebarFooter() {
  const { user, clearSession } = useAuth()
  const labelId = useId()

  return (
    <section aria-labelledby={labelId} className="border-t border-slate-200 pt-4">
      <h2 id={labelId} className="px-3 mb-1 text-xs font-semibold text-slate-400 uppercase tracking-wider">
        Sesión
      </h2>
      <div className="px-3 mb-3">
        <p className="text-sm font-medium text-slate-900 leading-tight truncate">
          {user?.fullName ?? '—'}
        </p>
        {user?.position && (
          <p className="text-xs text-slate-500 leading-tight truncate mt-0.5">
            {user.position}
          </p>
        )}
      </div>
      <button
        type="button"
        onClick={clearSession}
        className="w-full inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-100 hover:text-slate-900 transition-colors"
      >
        <LogOut className="w-4 h-4" aria-hidden="true" />
        Cerrar sesión
      </button>
    </section>
  )
}
