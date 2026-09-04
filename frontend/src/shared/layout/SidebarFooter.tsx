import { useId } from 'react'
import { LogOut, Moon, Sun } from 'lucide-react'
import { useAuth } from '../auth/AuthContext'
import { useTheme } from '../ui/theme/ThemeContext'

/**
 * Footer del sidebar con info de sesión + el interruptor del tema + botón "Cerrar sesión".
 * Las acciones de cuenta (ej. cambiar contraseña) viven en la sección
 * "Administrar cuenta" del menú principal — separadas del logout para
 * evitar mezclar acciones cotidianas con la salida de sesión.
 */
export function SidebarFooter() {
  const { user, clearSession } = useAuth()
  const { theme, toggleTheme } = useTheme()
  const labelId = useId()
  const oscuro = theme === 'dark'

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
      {/*
        El interruptor del tema va acá y no en el encabezado de una pantalla porque este es
        el único lugar visible desde todas, y porque el pie de la barra ya es el cajón de lo
        que es de la sesión y no del trabajo: la cuenta y la salida.

        Lleva TEXTO además del ícono, y el texto NO cambia con el estado: el nombre de un
        botón de alternancia se queda quieto y el estado va en `aria-pressed`. La primera
        versión daba vuelta el texto Y ponía `aria-pressed`, y las dos cosas juntas hacían
        que un lector de pantalla anunciara "Modo claro, presionado" justo cuando el oscuro
        estaba activo: exactamente lo contrario de lo que pasaba. Lo levantó la revisión.
      */}
      <button
        type="button"
        onClick={toggleTheme}
        aria-pressed={oscuro}
        className="w-full inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium text-fg-body hover:bg-surface-muted hover:text-fg transition-colors"
      >
        {oscuro ? (
          <Sun className="w-4 h-4" aria-hidden="true" />
        ) : (
          <Moon className="w-4 h-4" aria-hidden="true" />
        )}
        Modo oscuro
      </button>
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
