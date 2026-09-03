import { cn } from '../utils/cn'

export type AlertVariant = 'danger' | 'warning' | 'info' | 'success'

/**
 * El componente se queda con el ROL DE COLOR y nada más: fondo, texto y borde. El
 * espaciado, el tamaño de letra y la disposición los sigue poniendo cada sitio.
 *
 * No es pereza, es lo que la medición obliga. Las 35 alertas del árbol comparten el color
 * pero no la forma: hay cinco espaciados distintos (`px-4 py-2.5`, `px-4 py-3`, `p-4`,
 * `px-3.5 py-3`, `px-5 py-4`), dos radios y tres de ellas no llevan `text-sm`. Meter un
 * espaciado en el componente cambiaría el aspecto de las otras cuatro formas, que es
 * justo lo que esta mudanza no hace.
 */
const BASE = 'rounded-lg'

/**
 * El color de FONDO, que va siempre. El del texto NO entra acá, y esa es la decisión menos
 * obvia de este componente.
 *
 * El motivo: dos de los treinta y cuatro avisos no ponen color de texto en el contenedor y
 * dejan que cada hijo ponga el suyo. Meterlo en la variante les inventaría un color heredado
 * que hoy no tienen. Se revisaron los descendientes de esos dos y todos declaran el suyo, así
 * que el cambio probablemente no se vería; pero "probablemente no se ve" no es la vara de una
 * mudanza, y hay tres tonos distintos en juego (700, 800 y 900 según el sitio) que una sola
 * variante no puede reproducir. El color de texto queda en cada sitio tal como estaba, y lo
 * tokeniza el barrido de colores sueltos, que es el PR que va a medir los tres tonos juntos.
 */
const TONO: Record<AlertVariant, string> = {
  danger: 'bg-danger-soft',
  warning: 'bg-warning-soft',
  info: 'bg-accent-soft',
  success: 'bg-success-soft',
}

/**
 * El color del borde, que solo va cuando hay borde. Los cuatro salen de un token: la familia
 * informativa y la de éxito estrenan el suyo en este PR, con el mismo criterio que el hover
 * destructivo del PR del botón (token propio antes que tono crudo o prestado). Los valores
 * son los que las cajas ya usaban, medidos contra la paleta instalada.
 */
const BORDE: Record<AlertVariant, string> = {
  danger: 'border border-danger-border',
  warning: 'border border-warning-border',
  info: 'border border-accent-border',
  // `success` ENTRA SIN NINGÚN USO: hoy no hay una sola alerta de éxito en el árbol
  // (`grep -rhoE "bg-(emerald|green)-50\\b" src --include=*.tsx` da cero; sin el
  // límite de palabra da 1, que es el `bg-emerald-500` del `Stepper`). Se agrega porque el
  // token existe y las notificaciones de `sonner` usan ese rol. Su borde estrena
  // `success-border` en este mismo PR, en vez de reusar el tono suave: un borde del mismo
  // color que el fondo no es un borde. Cuando aparezca el primer uso real hay que mirarlo
  // en pantalla, porque nadie lo vio todavía.
  success: 'border border-success-border',
}

export function alertClasses({
  variant = 'danger',
  bordered = true,
}: { variant?: AlertVariant; bordered?: boolean } = {}): string {
  return cn(BASE, TONO[variant], bordered && BORDE[variant])
}
