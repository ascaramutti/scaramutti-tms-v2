import type { ComponentPropsWithRef } from 'react'
import { cn } from '../utils/cn'
import { buttonClasses } from './buttonClasses'
import type { ButtonSize, ButtonVariant } from './buttonClasses'

/**
 * `ComponentPropsWithRef` y no `ButtonHTMLAttributes` porque hay un llamador que le pone
 * `ref` para devolver el foco al botón de confirmar cuando se despeja un aviso de conflicto
 * que estaba en pantalla (`ServiceExitModal`), y así el usuario pueda reintentar sin
 * buscarlo. Al ABRIR el diálogo el foco va al campo del motivo, no acá. En React 19 el `ref` es una prop común y viaja en `...rest`, pero
 * solo si el tipo lo declara; con `ButtonHTMLAttributes` el `ref` no compila.
 */
interface ButtonProps extends ComponentPropsWithRef<'button'> {
  /** Peso de la acción. Tres, medidas contra los usos reales. */
  variant?: ButtonVariant
  /**
   * `icon` es el botón cuadrado de solo ícono; el resto es `md`. **Todavía sin llamador en
   * el producto**: se pidió por el paginado de `DataTable`, y al medirlo resultó ser una
   * cuarta forma (sin relleno, sin borde y sin anillo de foco, con su propia opacidad de
   * deshabilitado) que ninguna de las tres variantes reproduce; las clases exactas están en
   * `DataTable.tsx`. Combinado con cualquiera de las tres variantes de hoy, `icon` les
   * agregaría relleno, borde y anillo: NO puede servir a ese caso tal como está. Su primer
   * uso llega el día que exista una variante sin relleno.
   */
  size?: ButtonSize
}

/**
 * Botón compartido. Reemplaza a las tres constantes de clases que vivían en
 * `buttonStyles.ts` (borrado en este mismo cambio), con los mismos valores expresados en
 * tokens del tema.
 *
 * Las clases son las de las constantes viejas token por token, con TRES excepciones, todas
 * previstas en el mapa de `index.css`:
 *
 *   1. el hover del destructivo pasa a `danger-hover`: mismo valor, token propio;
 *   2. el foco de la primaria y la secundaria usa `focus`, que ya era el mismo azul;
 *   3. el anillo de foco del destructivo pasaba por el rojo 500 (#fb2c36) y ahora usa el
 *      token `danger`, que es el rojo 600 (#e7000b). Esta SÍ cambia de tono, y es uno de los
 *      81 cambios que el mapa de `index.css` declaraba; este PR consume uno de esos 14 y
 *      deja 13 para el barrido. Contra el blanco del modal el contraste sube de 3.81:1 a
 *      4.77:1, los dos por encima del 3:1 que pide el criterio 1.4.11. Se ve en CINCO lugares, no cuatro: los cuatro confirmar
 *      destructivos (quitar refuerzo, cancelar viaje, eliminar viaje y registrar rechazo),
 *      que están sobre el blanco del modal, y el disparador "Rechazada" de la barra del
 *      detalle de cotización, que no confirma nada (abre el modal) y vive sobre el lienzo
 *      gris de la página: ahí el cociente va de 3.64:1 a 4.56:1, también los dos por encima
 *      del 3:1. Mientras el barrido de colores sueltos no llegue, en los tres diálogos que
 *      llevan campo (rechazo de cotización y las dos salidas de viaje) conviven dos rojos: el
 *      botón ya usa el 600, y el 500 sigue en el anillo de foco del campo en error, que sale
 *      de `Textarea`, y en el del botón de reintento de `ServiceStatusErrorAlert`, que se
 *      renderiza dentro del modal de salida. En el de quitar refuerzo, que no tiene campos,
 *      el 600 queda solo. Y entre pantallas: los otros dos confirmar destructivos del
 *      sistema, los de anulación de almacén, no pasan por este componente y siguen con el
 *      anillo en el 500. Los dos valores pasan el 3:1, así que es inconsistencia, no falla.
 *
 *      Los nombres de esas dos clases NO se escriben acá a propósito: Tailwind escanea
 *      también los comentarios, y nombrar una utilidad que el código no usa la publica en el
 *      CSS. Medido: la primera redacción de este párrafo las nombraba SIN el prefijo de
 *      estado, y esas dos formas sin prefijo, que ningún elemento aplica, aparecieron en el
 *      bundle. Con el prefijo no habría pasado nada, porque esas sí las usa el código. Se
 *      detecta comparando el CSS compilado contra el de la rama base.
 *
 * Fuera de esa tercera, en modo claro no cambia un pixel en pantallas sRGB. En pantallas de
 * gama ancha (P3) hay una diferencia imperceptible que **este PR estrena**, no que herede:
 * hasta acá ningún componente usaba un token de color, así que los hexadecimales del tema no
 * habían llegado a pantalla. Los `oklch()` de los rojos 600 y 700 y del azul 500 caen fuera
 * del gamut sRGB, así que el hexadecimal del token es su recorte (ΔE-OK 0.0100, 0.0073 y
 * 0.0087, por debajo del umbral de perceptibilidad de ~0.02). El del azul alcanza el anillo
 * de foco de TODAS las primarias y secundarias, no solo a los cinco de la tercera excepción.
 *
 * `type="button"` por omisión, y NO es un detalle de estilo: un `<button>` sin `type`
 * dentro de un `<form>` envía el formulario. Es una prevención, no un arreglo: los 45
 * botones que este componente reemplaza traían todos su `type` a mano. Para enviar hay que
 * pedirlo con `type="submit"`.
 *
 * Todo lo demás (`aria-label`, `disabled`, `onClick`, `ref`, `form`, `title`…) pasa al
 * `<button>` real por `...rest`. Los que la suite busca por rol o nombre accesible se
 * notan solos si se pierden; `aria-disabled` no era uno de ellos y tiene su propio caso
 * en `QuotationDetailActions.test.tsx`, medido: sin él, borrarlo dejaba el archivo en
 * verde.
 */
export function Button({
  variant = 'primary',
  size = 'md',
  type = 'button',
  className,
  children,
  ...rest
}: ButtonProps) {
  return (
    <button type={type} className={cn(buttonClasses({ variant, size }), className)} {...rest}>
      {children}
    </button>
  )
}
