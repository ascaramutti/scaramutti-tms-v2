import { cn } from '../utils/cn'

interface SpinnerProps {
  /** Tamaño en pixeles. Default 18. */
  size?: number
  /** Etiqueta accesible para lectores de pantalla. Default "Cargando". */
  label?: string
  /** Clases extras (color, margin, etc.). */
  className?: string
}

/**
 * Spinner CSS-only. Usa `animate-spin` built-in de Tailwind. El color
 * proviene de `currentColor`, asi que hereda el color del texto del parent.
 */
export function Spinner({ size = 18, label = 'Cargando', className }: SpinnerProps) {
  return (
    <span
      role="status"
      aria-live="polite"
      aria-label={label}
      style={{ width: size, height: size }}
      className={cn(
        'inline-block animate-spin rounded-full border-2 border-current border-t-transparent align-middle',
        className,
      )}
    />
  )
}
