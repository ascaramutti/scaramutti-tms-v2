import { cn } from '../utils/cn'

/**
 * El vocabulario de clases del control de formulario: el mismo borde, fondo, foco y estado
 * de error para el input, el select, el área de texto, el campo de fecha y los veintiún
 * controles que las barras de filtro y las tablas de ítems declaran por su cuenta.
 *
 * Se expone por piezas y no como una sola cadena porque **los controles no comparten toda la
 * forma**: se midieron las nueve variantes del árbol y difieren en tres cosas concretas, el
 * espaciado, el `placeholder` y el tratamiento del deshabilitado. Una sola cadena obligaría
 * a que unos cuantos ganaran clases que hoy no tienen.
 *
 * La forma no cambia: ni la caja, ni el espaciado, ni el ancho, ni cuándo aparece el error.
 * El TONO sí cambia en dieciocho usos, y no por decisión de este archivo: son los que el mapa
 * de tokens de `index.css` ya tenía contados como "usos que cambian de tono". El rojo del
 * anillo y del borde de foco en error pasa de 500 a 600 (seis y seis), y el gris del texto
 * del control de solo lectura pasa de 600 a 700 (seis). Ese inventario se actualiza en
 * el mismo PR que lo consume; los números salen de contar las clases crudas que quedan.
 */
export type FieldDensity = 'comfortable' | 'compact'

/**
 * Lo que TODOS comparten: la caja del control.
 *
 * El ANCHO no está acá. Ocho de los nueve controles del árbol son `w-full`, pero el de la
 * barra de reportes no lleva ancho porque va en una fila de ancho fijo, y meterlo en el molde
 * le cambiaría el ancho a esa barra. El ancho es una decisión del sitio, no de la forma del
 * control, así que cada uno declara el suyo.
 */
export const FIELD_BASE = 'rounded-lg border bg-surface text-sm text-fg'

/** El foco, idéntico en los nueve. */
export const FIELD_FOCUS = 'focus:outline-none focus:ring-2 focus:ring-focus focus:border-focus'

/** El borde en reposo y el de error. */
export const FIELD_BORDER = 'border-border-strong'
export const FIELD_BORDER_INVALID = 'border-danger-border-strong'

/** El foco en error, que solo tienen el campo de texto y el área de texto. */
export const FIELD_FOCUS_INVALID = 'focus:ring-danger focus:border-danger'

/** El color del texto de sugerencia, que solo tienen los que aceptan placeholder. */
export const FIELD_PLACEHOLDER = 'placeholder:text-fg-subtle'

/** El deshabilitado, que solo tiene el campo de texto. */
export const FIELD_DISABLED =
  'disabled:bg-surface-subtle disabled:text-fg-subtle disabled:cursor-not-allowed'

/**
 * Dos espaciados, los dos que el árbol ya tenía: el cómodo lo usan los 81 campos de los
 * formularios y el compacto los 21 controles de las barras de filtro y las tablas de ítems,
 * donde el control convive con una tabla y un alto menor es lo que lo mantiene en la fila.
 */
export const FIELD_DENSITY: Record<FieldDensity, string> = {
  comfortable: 'px-3.5 py-2.5',
  compact: 'px-3 py-2',
}

/**
 * La forma común: caja, espaciado, foco y borde. Es exactamente lo que tienen el select, el
 * campo de fecha y los controles de las barras. El campo de texto le suma sus tres extras.
 */
export function fieldClasses({
  density = 'comfortable',
  invalid = false,
}: { density?: FieldDensity; invalid?: boolean } = {}): string {
  return cn(FIELD_BASE, FIELD_DENSITY[density], FIELD_FOCUS,
            invalid ? FIELD_BORDER_INVALID : FIELD_BORDER)
}

/**
 * El control de solo lectura: mismo molde, pero apagado y sin foco. Lo usan cuatro sitios
 * que muestran un dato ya elegido (el cliente del viaje, el de la cotización) dentro de un
 * campo, para que se lea como un campo y no como texto suelto.
 */
export function fieldReadonlyClasses({ density = 'comfortable' }: { density?: FieldDensity } = {}): string {
  return cn('cursor-default rounded-lg border border-border bg-surface-subtle text-sm text-fg-body',
            FIELD_DENSITY[density], 'focus:outline-none')
}

/**
 * La casilla de verificación. Es un control y va al molde, aunque no comparta la caja: su
 * borde y su color de marca son los mismos tokens que el resto.
 */
export const FIELD_CHECKBOX = 'h-4 w-4 rounded border-border-strong text-accent focus:ring-focus'

/** La etiqueta que va arriba del control. */
export const FIELD_LABEL = 'mb-1.5 block text-sm font-medium text-fg-body'

/** El mensaje de error bajo el control. */
export const FIELD_ERROR = 'mt-1.5 text-sm text-danger'

/** La ayuda bajo el control, que no es un error. */
export const FIELD_HELPER = 'mt-1.5 text-xs text-fg-muted'
