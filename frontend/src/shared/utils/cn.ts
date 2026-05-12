import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/**
 * Combina clases condicionales (clsx) y resuelve conflictos de Tailwind
 * (tailwind-merge). Patrón estándar para componentes con clases dinámicas.
 *
 * Ejemplo:
 *   cn('px-2 py-1', isActive && 'bg-blue-500', 'px-4')
 *   → 'py-1 bg-blue-500 px-4'  (px-4 sobrescribe a px-2)
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs))
}
