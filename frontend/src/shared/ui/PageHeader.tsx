import type { ReactNode } from 'react'
import { cn } from '../utils/cn'

interface PageHeaderProps {
  title: string
  description?: string
  /** Slot de acción a la derecha (ej. botón "Nueva cotización"). */
  action?: ReactNode
  /** Si true, dibuja una línea divisoria debajo del header. */
  divider?: boolean
}

/**
 * Cabecera de pantalla: título (h1) + descripción opcional + slot de acción.
 * Convención del proyecto: un solo `<h1>` por pantalla (a11y).
 */
export function PageHeader({ title, description, action, divider }: PageHeaderProps) {
  return (
    <header
      className={cn(
        'flex flex-wrap items-start justify-between gap-4',
        divider && 'border-b border-slate-200 pb-5',
      )}
    >
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">{title}</h1>
        {description && <p className="mt-1 text-sm text-slate-500">{description}</p>}
      </div>
      {action && <div className="flex-shrink-0">{action}</div>}
    </header>
  )
}
