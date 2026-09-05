import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/**
 * Combina clases condicionales (clsx) y resuelve conflictos de Tailwind
 * (tailwind-merge). Patrón estándar para componentes con clases dinámicas.
 *
 * Lo que hay que saber al usarla: dentro de un mismo grupo de utilidades **gana la última**.
 * Si un componente pone un espaciado horizontal y el sitio que lo llama pone otro, queda el
 * del sitio; lo mismo con un color de borde, que es donde más caro sale. Las condiciones
 * falsas se descartan y el resto del conjunto no se toca.
 *
 * Este comentario no escribe ninguna clase de ejemplo a propósito: Tailwind escanea los
 * comentarios y publica una regla por cada utilidad que encuentra, la use alguien o no.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs))
}
