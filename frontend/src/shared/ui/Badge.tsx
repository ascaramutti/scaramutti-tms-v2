import type { ReactNode } from 'react'
import { cn } from '../utils/cn'

export type BadgeVariant =
  | 'default'
  | 'info'
  | 'success'
  | 'warning'
  | 'danger'
  | 'progress'
  | 'transition'

/**
 * `bordered` solo existe para la informativa, y el tipo lo obliga: es el marcador "interno"
 * de las observaciones que no ve el cliente, en cuatro lugares, y es el único filete de
 * pastilla del árbol. Sin esta unión, `bordered` en una pastilla roja le pondría un borde
 * azul, y una prop que se puede pedir para todas las variantes obliga a inventarle un color
 * de borde a cada una: siete reglas de CSS publicadas para cero usos.
 */
type BadgeProps = { children: ReactNode } & (
  | { variant: 'info'; bordered?: boolean }
  | { variant?: Exclude<BadgeVariant, 'info'>; bordered?: never }
)

const VARIANT_CLASSES: Record<BadgeVariant, string> = {
  default: 'bg-surface-muted text-fg-body',
  info: 'bg-accent-soft-strong text-accent-hover',
  success: 'bg-success-soft text-success-fg',
  warning: 'bg-warning-soft-strong text-warning',
  // `danger` era ROSA y ahora es rojo, unificado con el botón destructivo y la alerta de
  // peligro: el rosa no tenía fila en el mapa y era la única familia que decía "peligro" con
  // otro color que el resto del sistema. Ruling del dueño 2026-09-03.
  danger: 'bg-danger-soft text-danger-fg',
  // Las dos que sobreviven al recorte de las decorativas, con nombre por función: no hay
  // cinco semánticas para seis estados de viaje, así que fundirlas volvía iguales dos
  // estados que hoy se distinguen de un vistazo en el listado.
  progress: 'bg-progress-soft text-progress-fg',
  transition: 'bg-transition-soft text-transition-fg',
}

/**
 * El filete del marcador "interno". Nació con un azul de la paleta escrito a mano, un paso
 * más claro que este token, porque ese tono no tenía ninguno; el barrido de colores sueltos
 * lo unió al borde de la caja informativa, que cumple el mismo rol (un filete azul sobre
 * relleno azul suave). Ruling del dueño 2026-09-03. El tono no se nombra acá a propósito:
 * Tailwind escanea los comentarios y nombrar una utilidad la publica en el CSS aunque ya no
 * la use nadie, que es como este archivo estrenó una regla muerta en el PR del botón.
 */
const INFO_BORDER = 'border border-accent-border'

/**
 * Etiqueta de estado reutilizable (pill). El color nunca es el único portador
 * de significado — siempre acompaña texto legible.
 *
 * El `gap-1` está en la base y no en la prop del filete: solo separa hijos, así que en una
 * pastilla de un solo texto no hace nada, y es lo que necesita el marcador "interno", que
 * lleva un candado antes de la palabra.
 */
export function Badge({ children, variant = 'default', bordered = false }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium',
        VARIANT_CLASSES[variant],
        bordered && INFO_BORDER,
      )}
    >
      {children}
    </span>
  )
}
