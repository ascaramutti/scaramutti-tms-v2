import { useId } from 'react'
import { LogOut } from 'lucide-react'
import { useAuth } from '../auth/AuthContext'

/**
 * Footer del sidebar con info de sesión + botón "Cerrar sesión".
 * Las acciones de cuenta (ej. cambiar contraseña) viven en la sección
 * "Administrar cuenta" del menú principal — separadas del logout para
 * evitar mezclar acciones cotidianas con la salida de sesión.
 */
export function SidebarFooter() {
  const { user, clearSession } = useAuth()
  const labelId = useId()

  return (
    <section aria-labelledby={labelId} className="border-t border-border pt-4">
      <h2 id={labelId} className="px-3 mb-1 text-xs font-semibold text-fg-subtle uppercase tracking-wider">
        Sesión
      </h2>
      <div className="px-3 mb-3">
        <p className="text-sm font-medium text-fg leading-tight truncate">
          {user?.fullName ?? '—'}
        </p>
        {user?.position && (
          <p className="text-xs text-fg-muted leading-tight truncate mt-0.5">
            {user.position}
          </p>
        )}
      </div>
      <button
        type="button"
        onClick={clearSession}
        className="w-full inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium text-fg-body hover:bg-surface-muted hover:text-fg transition-colors"
      >
        <LogOut className="w-4 h-4" aria-hidden="true" />
        Cerrar sesión
      </button>
    </section>
  )
}
