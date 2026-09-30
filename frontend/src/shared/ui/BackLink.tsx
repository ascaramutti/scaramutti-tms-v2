import type { ReactNode } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link } from 'react-router-dom'

interface BackLinkProps {
  /** Ruta destino (ej. el listado). */
  to: string
  children: ReactNode
  /** Mientras un envío está en vuelo: salir dejaría el resultado sin nadie que lo vea. */
  disabled?: boolean
}

/**
 * Enlace "volver" (flecha + texto) del breadcrumb superior. Componente único para
 * mantener consistentes las vistas que lo usan (detalle y wizard de cotizaciones).
 */
export function BackLink({ to, children, disabled = false }: BackLinkProps) {
  if (disabled) {
    return (
      <span
        aria-disabled="true"
        className="inline-flex cursor-not-allowed items-center gap-1.5 text-sm font-medium text-fg-muted opacity-60"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        {children}
        <span className="sr-only"> (no disponible)</span>
      </span>
    )
  }
  return (
    <Link
      to={to}
      className="inline-flex items-center gap-1.5 rounded text-sm font-medium text-fg-muted hover:text-fg-body focus:outline-none focus:ring-2 focus:ring-focus"
    >
      <ArrowLeft className="h-4 w-4" aria-hidden="true" />
      {children}
    </Link>
  )
}
