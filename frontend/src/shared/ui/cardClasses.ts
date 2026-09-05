import { cn } from '../utils/cn'

export type CardPadding = 'lg' | 'md' | 'none'

/**
 * La forma de tarjeta del sistema: fondo, borde y sombra. Medida sobre `develop` antes de
 * extraerla: 57 sitios la escriben a mano, con tres espaciados y ninguna otra variación.
 *
 * Vive en su propio módulo y no junto a `Card` por la regla
 * `react-refresh/only-export-components` del lint: un archivo que exporta un componente no
 * puede exportar además una función.
 */
const BASE = 'rounded-xl border border-border bg-surface'

/**
 * La sombra es el segundo eje, y no lo inventé: de las 28 tarjetas del árbol, 25 la llevan
 * y **tres no** (los paneles internos del asistente de cotizaciones, que ya viven dentro de
 * otra tarjeta y donde una segunda sombra marcaría un relieve que hoy no existe). Por
 * omisión va, porque es lo que hacen 25 de 28.
 *
 * Se llama `elevated` y no como la utilidad de sombra a secas, a propósito: ese nombre corto
 * ES una utilidad válida de Tailwind, y el escáner lo encuentra dentro del `={false}` del
 * JSX y publica una regla que ningún elemento aplica. Medido con el diff del CSS compilado
 * contra `develop`, que es lo único que lo ve: ni la suite, ni el typecheck, ni el lint.
 * Por lo mismo, este comentario tampoco la escribe.
 */
const SOMBRA = 'shadow-sm'

/**
 * Tres espaciados y nada más. `none` es el que usan la tabla, las listas y las tarjetas con
 * encabezado propio: ponen su padding adentro, en cada franja, porque el borde tiene que
 * llegar al ancho completo.
 */
const PADDINGS: Record<CardPadding, string> = {
  lg: 'p-5',
  md: 'p-4',
  none: '',
}

export function cardClasses({
  padding = 'lg',
  elevated = true,
}: { padding?: CardPadding; elevated?: boolean } = {}): string {
  return cn(BASE, PADDINGS[padding], elevated && SOMBRA)
}
