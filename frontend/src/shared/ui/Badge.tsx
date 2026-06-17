import type { ReactNode } from 'react'
import { cn } from '../utils/cn'

export type BadgeVariant =
  | 'default'
  | 'info'
  | 'success'
  | 'warning'
  | 'danger'
  | 'slate'
  | 'teal'

interface BadgeProps {
  children: ReactNode
  variant?: BadgeVariant
}

const VARIANT_CLASSES: Record<BadgeVariant, string> = {
  default: 'bg-slate-100 text-slate-700',
  info: 'bg-blue-100 text-blue-700',
  success: 'bg-emerald-100 text-emerald-700',
  warning: 'bg-amber-100 text-amber-700',
  danger: 'bg-rose-100 text-rose-700',
  slate: 'bg-slate-100 text-slate-700',
  teal: 'bg-teal-100 text-teal-700',
}

/**
 * Etiqueta de estado reutilizable (pill). El color nunca es el único portador
 * de significado — siempre acompaña texto legible.
 */
export function Badge({ children, variant = 'default' }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium',
        VARIANT_CLASSES[variant],
      )}
    >
      {children}
    </span>
  )
}
