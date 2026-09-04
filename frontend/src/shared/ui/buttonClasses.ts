import { cn } from '../utils/cn'

export type ButtonVariant = 'primary' | 'secondary' | 'danger'
export type ButtonSize = 'md' | 'icon'

const BASE = 'inline-flex items-center rounded-lg focus:outline-none focus:ring-2'

const TAMANOS: Record<ButtonSize, string> = {
  md: 'px-4 py-2 text-sm font-medium',
  icon: 'p-1.5',
}

const VARIANTES: Record<ButtonVariant, string> = {
  primary:
    'bg-accent text-on-solid shadow-sm hover:bg-accent-hover focus:ring-focus focus:ring-offset-2 focus:ring-offset-surface',
  secondary:
    'border border-border-strong bg-surface text-fg-body hover:bg-surface-subtle focus:ring-focus',
  danger:
    'bg-danger text-on-solid shadow-sm hover:bg-danger-hover focus:ring-danger focus:ring-offset-2 focus:ring-offset-surface',
}

/**
 * Las clases del botón, sin el botón.
 *
 * Vive en su propio módulo y no junto a `Button` por la regla
 * `react-refresh/only-export-components` del lint: un archivo que exporta un componente no
 * puede exportar además funciones, porque el refresco en caliente pierde el estado.
 *
 * Existe porque algunos de los usos que este componente reemplaza NO son botones: hay
 * enlaces de navegación (que conservan su rol y su href; convertirlos en `<button>` sería
 * un defecto de accesibilidad) y hay mapas de variantes que aplican la cadena con una
 * plantilla. Todos usan estas mismas tres variantes, así que la cadena se expone en vez de
 * duplicarse: una sola fuente de verdad, y el componente la consume por dentro. La prueba
 * fija que las dos no puedan divergir, con una fila por variante.
 */
export function buttonClasses({
  variant = 'primary',
  size = 'md',
}: { variant?: ButtonVariant; size?: ButtonSize } = {}): string {
  return cn(BASE, TAMANOS[size], VARIANTES[variant])
}
