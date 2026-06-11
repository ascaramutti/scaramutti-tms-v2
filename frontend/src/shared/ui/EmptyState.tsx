import type { ReactNode } from 'react'
import { Inbox, type LucideIcon } from 'lucide-react'

interface EmptyStateProps {
  /** Ícono a mostrar. Default `Inbox`. */
  icon?: LucideIcon
  title: string
  description?: string
  /** Acción opcional (ej. botón "Limpiar filtros"). */
  action?: ReactNode
}

/**
 * Estado vacío genérico para listados. Distinto del estado de error: esto es
 * "no hay datos" (o "no hay resultados para el filtro"), no "falló la carga".
 */
export function EmptyState({ icon: Icon = Inbox, title, description, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-16 text-center">
      <Icon className="h-12 w-12 text-slate-300" aria-hidden="true" />
      <p className="mt-4 text-sm font-medium text-slate-700">{title}</p>
      {description && <p className="mt-1 text-sm text-slate-500">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}
