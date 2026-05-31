import type { ReactNode } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link } from 'react-router-dom'

interface BackLinkProps {
  /** Ruta destino (ej. el listado). */
  to: string
  children: ReactNode
}

/**
 * Enlace "volver" (flecha + texto) del breadcrumb superior. Componente único para
 * mantener consistentes las vistas que lo usan (detalle y wizard de cotizaciones).
 */
export function BackLink({ to, children }: BackLinkProps) {
  return (
    <Link
      to={to}
      className="inline-flex items-center gap-1.5 rounded text-sm font-medium text-slate-500 hover:text-slate-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
    >
      <ArrowLeft className="h-4 w-4" aria-hidden="true" />
      {children}
    </Link>
  )
}
