import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { beforeAll, describe, expect, it } from 'vitest'
import { compileGlobalCss } from './compileCss'
import { contrastRatio, parseHex } from './contrast'
import { readTailwindPalette, toHex } from './palette'
import { parseThemeColors, parseThemeOverrides } from './readTokens'

/**
 * Mide el contraste de cada par texto/fondo que el tema declara.
 *
 * Por qué existe, y por qué no alcanza con los tests de accesibilidad que ya
 * hay: las 46 aserciones de axe de la suite NO pueden ver un color. Tres cosas
 * se lo impiden, y las tres se comprueban por separado:
 *   1. `vitest.config.ts` declara `css: false` y ningún test importa el CSS
 *      global, así que una clase de Tailwind no produce color calculado.
 *   2. Cuando axe no puede resolver el fondo no reporta una violación, reporta
 *      un resultado *incompleto* ("Unable to determine contrast ratio").
 *   3. El matcher de `vitest-axe` solo mira `results.violations`; la palabra
 *      `incomplete` no aparece en su código distribuido.
 * El conteo se mide así, descontando el comentario de esta misma línea:
 *   grep -rho "toHaveNoViolations" --include=*.test.ts --include=*.test.tsx src
 *
 * ALCANCE, y conviene ser exacto porque el verde de acá se lee como un permiso:
 * esta prueba mide los pares ENTRE TOKENS. Durante toda la serie eso dejaba afuera
 * los colores escritos a mano, que eran donde estaban los peores incumplimientos:
 * el paso ya completado del asistente pintaba blanco sobre un verde medio (2.47:1)
 * y la tarjeta de ítem usaba un rojo y un verde más claros que su token. El barrido
 * los convirtió a todos y la guarda del tope los deja en cero, así que ya no hay un
 * afuera de este tipo: hoy cada color de la pantalla es un token y entra acá o entra
 * en la lista de los que no se miden, con su razón.
 *
 * Tampoco mide que un par declarado sea el que la pantalla usa de verdad. Si
 * mañana una pantalla combina dos tokens que acá no están juntos, nadie lo va a
 * medir: LA LISTA HAY QUE MANTENERLA. La guarda de cobertura de más abajo lo
 * fuerza a medias, exigiendo que todo token aparezca al menos una vez.
 *
 * Sobre nombrar utilidades en los comentarios: Tailwind las publica desde
 * cualquier archivo que escanee, con uso real o sin él, y una primera versión de
 * este comentario que nombraba dos las hizo aparecer en el bundle. Esta carpeta
 * quedó fuera del escaneo justamente por eso, y hay una guarda que lo afirma
 * sobre la lista de archivos que el escáner devuelve, no sobre la directiva. Aun
 * así los tokens se nombran acá en prosa: la exclusión se puede caer, y esta
 * costumbre es lo que hace que caerse no publique nada.
 */

/** Texto normal, WCAG 2.1 AA, criterio 1.4.3. */
const AA_TEXT = 4.5
/** Elementos que no son texto pero comunican estado, criterio 1.4.11. */
const AA_NON_TEXT = 3

interface Pair {
  /** Token del texto (o del trazo, si no es texto). */
  fg: string
  /** Token del fondo sobre el que se dibuja. */
  bg: string
  min: number
  /** Dónde se ve esto en la aplicación. */
  what: string
}

const PAIRS: Pair[] = [
  { fg: 'fg', bg: 'canvas', min: AA_TEXT, what: 'título sobre el fondo de página' },
  { fg: 'fg', bg: 'surface', min: AA_TEXT, what: 'título y valor de KPI sobre tarjeta' },
  { fg: 'fg', bg: 'surface-muted', min: AA_TEXT, what: 'texto sobre superficie secundaria' },
  { fg: 'fg', bg: 'surface-subtle', min: AA_TEXT, what: 'texto sobre una caja de resumen inerte' },
  { fg: 'fg', bg: 'accent-soft', min: AA_TEXT, what: 'ícono de la tarjeta de tipo seleccionada, y texto de la caja de totales' },
  { fg: 'fg', bg: 'warning-soft', min: AA_TEXT, what: 'valor del tile de stock bajo cuando el filtro está activo' },
  { fg: 'fg', bg: 'success-soft', min: AA_TEXT, what: 'PREVENTIVO: la alerta de éxito todavía no existe' },
  { fg: 'fg', bg: 'danger-soft', min: AA_TEXT, what: 'PREVENTIVO: hoy los fondos de peligro solo llevan texto rojo' },
  { fg: 'fg-body', bg: 'surface', min: AA_TEXT, what: 'etiqueta de campo y celda de tabla' },
  { fg: 'fg-body', bg: 'canvas', min: AA_TEXT, what: 'texto de cuerpo sobre el fondo' },
  { fg: 'fg-body', bg: 'surface-muted', min: AA_TEXT, what: 'texto de la pastilla neutra' },
  { fg: 'fg-body', bg: 'surface-subtle', min: AA_TEXT, what: 'celda de tabla con el mouse encima' },
  { fg: 'fg-muted', bg: 'surface', min: AA_TEXT, what: 'texto secundario sobre tarjeta' },
  { fg: 'fg-muted', bg: 'canvas', min: AA_TEXT, what: 'descripción bajo el título de pantalla' },
  { fg: 'fg-muted', bg: 'surface-muted', min: AA_TEXT, what: 'texto secundario sobre la franja gris' },
  { fg: 'fg-muted', bg: 'surface-subtle', min: AA_TEXT, what: 'encabezado de tabla y caja de solo lectura, el par más ajustado del sistema' },
  { fg: 'fg-muted', bg: 'accent-soft', min: AA_TEXT, what: 'celda secundaria de la fila integral' },
  { fg: 'fg-muted', bg: 'warning-soft', min: AA_TEXT, what: 'rótulo del tile de stock bajo cuando el filtro está activo' },
  { fg: 'fg-body', bg: 'accent-soft', min: AA_TEXT, what: 'texto de la caja de totales, en su tono de cuerpo' },
  { fg: 'warning-fg', bg: 'surface', min: AA_TEXT, what: 'texto del botón de forzar' },
  { fg: 'accent-hover', bg: 'surface-subtle', min: AA_TEXT, what: 'opción de crear, con el mouse encima' },
  { fg: 'fg-subtle', bg: 'surface', min: AA_TEXT, what: 'placeholder del input' },
  { fg: 'fg-subtle', bg: 'canvas', min: AA_TEXT, what: 'texto apagado sobre el fondo de página' },
  { fg: 'fg-subtle', bg: 'surface-subtle', min: AA_TEXT, what: 'el texto de sugerencia sobre una caja inerte. NO el de un control deshabilitado, que desde que lleva opacidad no se dibuja a este valor: como el velo, lo que se ve es una mezcla, y la norma exime a los controles inactivos' },
  { fg: 'fg-subtle', bg: 'accent-soft', min: AA_TEXT, what: 'el guion de "sin observaciones" dentro de la caja de notas de la cotización' },
  { fg: 'surface-subtle', bg: 'surface', min: AA_NON_TEXT, what: 'foco de teclado de la fila clickeable' },
  { fg: 'danger-border-strong', bg: 'danger-soft', min: AA_NON_TEXT, what: 'borde del botón de descartar, dentro de la alerta roja' },
  { fg: 'border-strong', bg: 'canvas', min: AA_NON_TEXT, what: 'borde del botón secundario, que va sobre el fondo de página' },
  { fg: 'on-solid', bg: 'accent', min: AA_TEXT, what: 'texto del botón primario' },
  { fg: 'on-solid', bg: 'accent-hover', min: AA_TEXT, what: 'texto del botón primario al pasar el mouse' },
  { fg: 'on-solid', bg: 'accent-disabled', min: AA_TEXT, what: 'texto del botón primario apagado, en los siete lugares que lo tienen' },
  { fg: 'on-solid', bg: 'danger', min: AA_TEXT, what: 'texto del botón destructivo' },
  { fg: 'on-solid', bg: 'danger-hover', min: AA_TEXT, what: 'texto del botón destructivo con el mouse encima' },
  { fg: 'on-solid', bg: 'transition', min: AA_TEXT, what: 'texto del botón de aceptar una cotización' },
  { fg: 'on-solid', bg: 'transition-hover', min: AA_TEXT, what: 'ese mismo botón con el mouse encima' },
  { fg: 'on-solid', bg: 'success', min: AA_TEXT, what: 'el número dentro del círculo de un paso ya completado del asistente, que es el único relleno sólido verde con texto encima' },
  { fg: 'accent', bg: 'surface', min: AA_TEXT, what: 'enlace y texto de acento sobre tarjeta' },
  { fg: 'accent', bg: 'canvas', min: AA_NON_TEXT, what: 'anillo del spinner de carga sobre el fondo de página' },
  { fg: 'accent', bg: 'accent-soft', min: AA_TEXT, what: 'texto de la alerta informativa' },
  { fg: 'accent-hover', bg: 'surface', min: AA_TEXT, what: 'enlace en su tono fuerte, sobre tarjeta' },
  { fg: 'accent-hover', bg: 'canvas', min: AA_TEXT, what: 'etiqueta del paso visitado del asistente' },
  { fg: 'accent-hover', bg: 'surface-muted', min: AA_TEXT, what: 'opción del desplegable resaltada por teclado' },
  { fg: 'accent-hover', bg: 'accent-soft', min: AA_TEXT, what: 'total del asistente, ítem activo de la barra lateral y pastillas internas' },
  { fg: 'accent-hover', bg: 'accent-soft-strong', min: AA_TEXT, what: 'texto de la pastilla informativa y del círculo con el número de ítem' },
  // Dos usos, no uno: el texto del error y el anillo de foco del botón destructivo. El
  // umbral queda en el del texto, que es el más estricto de los dos (4.5 contra 3).
  { fg: 'danger', bg: 'surface', min: AA_TEXT, what: 'mensaje de error bajo un campo, y anillo de foco del destructivo sobre tarjeta' },
  { fg: 'danger', bg: 'canvas', min: AA_TEXT, what: 'mensaje de error sobre el fondo de página, y anillo de foco del destructivo sobre el lienzo' },
  { fg: 'danger-fg', bg: 'surface', min: AA_TEXT, what: 'texto de peligro en su tono fuerte, sobre tarjeta' },
  { fg: 'danger-fg', bg: 'danger-soft', min: AA_TEXT, what: 'texto de la alerta de peligro' },
  { fg: 'danger-fg', bg: 'danger-soft-strong', min: AA_TEXT, what: 'texto del botón de descartar cuando responde al mouse, dentro de la alerta' },
  { fg: 'danger-border-strong', bg: 'surface', min: AA_NON_TEXT, what: 'borde de un control en error' },
  { fg: 'warning-border-strong', bg: 'surface', min: AA_NON_TEXT, what: 'el borde punteado de la tarjeta de componente integral, y el relleno del botón de forzar contra su propio borde' },
  { fg: 'warning-border-strong', bg: 'warning-soft', min: AA_NON_TEXT, what: 'el borde del botón de forzar contra el banner ámbar que lo contiene: su otro lado, y el que faltaba. Contra su PROPIO relleno con el mouse encima da 2.87, que no se mide acá porque el límite que 1.4.11 pide ver es el de afuera, contra lo que lo rodea' },
  { fg: 'danger-border-strong', bg: 'canvas', min: AA_NON_TEXT, what: 'el borde del botón de anular, que va sobre el fondo de página' },
  { fg: 'warning', bg: 'surface', min: AA_TEXT, what: 'aviso de campo sobre tarjeta' },
  { fg: 'warning', bg: 'warning-soft', min: AA_TEXT, what: 'pastilla de aviso de Badge, y el valor del tile de stock bajo con el filtro activo' },
  { fg: 'warning', bg: 'warning-soft-strong', min: AA_TEXT, what: 'texto del chip que quita el filtro de stock bajo' },
  { fg: 'warning', bg: 'warning-soft-hover', min: AA_TEXT, what: 'ese mismo chip con el mouse encima' },
  { fg: 'warning-fg', bg: 'warning-soft-strong', min: AA_TEXT, what: 'texto del botón de forzar con el mouse encima, dentro del banner ámbar' },
  { fg: 'warning-fg', bg: 'warning-soft', min: AA_TEXT, what: 'texto de la alerta de aviso' },
  { fg: 'success', bg: 'surface', min: AA_TEXT, what: 'PREVENTIVO: no hay texto ni relleno con el tono sólido todavía' },
  { fg: 'success-fg', bg: 'surface', min: AA_TEXT, what: 'texto de entrada en el kardex' },
  { fg: 'success-fg', bg: 'success-soft', min: AA_TEXT, what: 'texto de la pastilla de éxito' },
  { fg: 'progress-fg', bg: 'progress-soft', min: AA_TEXT, what: 'texto de la pastilla del viaje en ruta' },
  { fg: 'transition-fg', bg: 'transition-soft', min: AA_TEXT, what: 'texto de la pastilla de la cotización aceptada y del cambio de estado en la bitácora' },
  { fg: 'focus', bg: 'surface', min: AA_NON_TEXT, what: 'anillo de foco sobre tarjeta' },
  { fg: 'focus', bg: 'canvas', min: AA_NON_TEXT, what: 'anillo de foco sobre el fondo' },
  // Los seis fondos que el anillo toca de verdad y que nadie medía. Los levantó la revisión del PR
  // del foco de teclado: la lista tenía los dos obvios, y el par que sostiene el arreglo principal
  // (el contorno de la fila enfocada, que cae sobre el realce y no sobre la tarjeta) no estaba.
  { fg: 'focus', bg: 'surface-subtle', min: AA_NON_TEXT, what: 'el contorno de la fila enfocada, que cae sobre su propio realce' },
  { fg: 'focus', bg: 'surface-muted', min: AA_NON_TEXT, what: 'el anillo de la opción resaltada del combo' },
  { fg: 'focus', bg: 'accent-soft', min: AA_NON_TEXT, what: 'el anillo del ítem activo de la barra de navegación' },
  { fg: 'focus', bg: 'warning-soft', min: AA_NON_TEXT, what: 'el anillo de los botones de texto dentro de un aviso ámbar' },
  { fg: 'focus', bg: 'danger-soft', min: AA_NON_TEXT, what: 'el anillo del botón de texto dentro de un aviso rojo' },
  { fg: 'focus', bg: 'warning-soft-strong', min: AA_NON_TEXT, what: 'el anillo del botón de forzar, enfocado y con el mouse encima: el par más ajustado del foco' },
  { fg: 'border-strong', bg: 'surface', min: AA_NON_TEXT, what: 'borde del input sobre tarjeta' },
]

/**
 * Los tokens que NO entran en ningún par, con su razón. La guarda de cobertura
 * exige que esta lista y la de los tokens sin par coincidan exacto, así que
 * agregar un token obliga a decidir: o se mide, o se justifica acá.
 *
 * El criterio que los junta es que ninguno COMUNICA por sí solo, que es lo que WCAG 1.4.11
 * pide medir con 3:1 (el borde de un control, el anillo de foco). Un filete que separa una
 * tarjeta del fondo, la sombra que la eleva, el velo que apaga lo de atrás y el trazo que
 * acompaña a un dato que ya se lee no dicen nada que no esté dicho de otra forma. Afirmarlos
 * contra un número inventado sería una prueba que no prueba nada.
 *
 * Una advertencia sobre esta lista: creció de una entrada a cuatro, y de cuatro a ocho cuando
 * el PR del contraste claro declaró decorativos los marcos de las alertas. Su justificación ya
 * se quedó vieja una vez, describiendo la versión de una. La guarda de cobertura no puede
 * verlo, porque los dos lados se mueven juntos; lo único que lo ataja es leerla al agregar.
 *
 * `danger-border` y `warning-border` estuvieron acá y salieron, por la razón
 * inversa: en los dos casos hay un botón con relleno propio cuyo borde es el único
 * límite del control. Los dos se miden y los dos fallan, como `border-strong`.
 */
const SIN_PAR: Record<string, string> = {
  border:
    'filete de tarjeta y tabla contra el fondo: separación decorativa. Y con él queda declarado el par que la tarjeta forma con la página, que la revisión buscó y no encontró en ninguna lista: no se mide a propósito, porque lo que separa una tarjeta del fondo no es su luminancia (1.05 en claro, 1.14 en oscuro) sino este filete y su sombra. Ponerle un mínimo sería inventarle un número a una decisión de dibujo.',
  overlay:
    'el velo del modal. No se mide como par porque nunca se dibuja a su valor pleno: el ' +
    'sitio le pone la opacidad, y lo que importa es cuánto separa a la tarjeta del fondo, ' +
    'que está medido acá y en el documento de diseño, componiéndolo a la opacidad que el ' +
    'sitio aplica y sobre el lienzo, que es donde se dibuja: **4.83 en claro y 1.25 en ' +
    'oscuro**, donde lo que recorta la tarjeta es su filete, con 1.74 contra el velo.',
  elevation:
    'el color de la sombra de una tarjeta elevada. Una sombra no comunica un estado ni lleva ' +
    'texto encima: es profundidad. Su único uso además está sobre una tarjeta que ya se ' +
    'recorta con un filete propio, así que la sombra tampoco es lo que la separa del fondo.',
  'danger-border':
    'el marco de la alerta roja, y nada más desde que los botones de anular pasaron al borde de ' +
    'control. Un marco cuyo mensaje ya se lee por el texto y por el fondo de la caja no comunica ' +
    'por sí solo: enmarca. Decisión del dueño 2026-09-05.',
  'warning-border':
    'el marco de la alerta ámbar y el de la caja de aviso del asistente, por la misma razón. Este ' +
    'token estuvo medido creyendo que era el borde del botón de forzar, y no lo era: ese botón usa ' +
    'el borde de control. Era una nota equivocada, no un par.',
  'accent-border':
    'tres roles, los tres decorativos: el marco de la caja informativa y el de la caja de notas, ' +
    'el filete de la pastilla, y un separador horizontal en el resumen del asistente. Ninguno ' +
    'comunica solo, y ninguno es el límite de un control: el botón de agregar componente, que sí ' +
    'lo era, pasó al acento pleno en el PR del contraste claro, y ese cambio es lo que hace ' +
    'verdadera esta nota. Antes no lo era, y la revisión lo levantó.',
  'success-border':
    'el marco del aviso de éxito. Mismo caso, y además hoy no hay una sola alerta de éxito en el ' +
    'árbol: el token entró para que la familia esté completa.',
  trace:
    'el trazo decorativo que no es texto ni borde: el ícono de una pantalla sin datos, que es ' +
    '`aria-hidden`, y la línea que une dos pasos del asistente, que acompaña a un número que ' +
    'sí se lee. Ninguno de los dos comunica solo, que es lo que 1.4.11 pide medir.',
}

/**
 * Los pares que el tema claro NO cumple, y que son ANTERIORES a que este archivo
 * existiera: todos vienen de las clases crudas que la aplicación usa desde el
 * principio.
 *
 * Están acá y no arreglados porque arreglarlos CAMBIA EL ASPECTO del modo claro,
 * y el PR que declara los tokens tiene una sola propiedad que lo hace seguro: si
 * algo cambia de aspecto, es un error. Ruling del dueño del 2026-09-02: se
 * arreglan al final, en un PR propio.
 *
 * Cada entrada fija el cociente REAL medido hoy. Eso hace que la excepción se
 * autodestruya: el día que alguien aclare u oscurezca uno de esos colores, el
 * número deja de coincidir, esta prueba falla y quien lo arregló tiene que
 * borrar la línea. Una excepción que no se puede olvidar.
 */
const EXCEPCIONES_CLARO: Record<string, { ratio: number; note: string }> = {
  'surface-subtle/surface': {
    ratio: 1.05,
    note:
      'El realce de la fila clickeable con el mouse encima, y también cuando el teclado la ' +
      'alcanza. A 1.05 no se distingue de la fila en reposo, y durante un tiempo fue la ÚNICA ' +
      'señal de foco que la fila tenía, porque además apagaba el contorno del navegador: el ' +
      'teclado no podía usarse. Eso se arregló dándole contorno propio con el token de foco, que ' +
      'sí se mide, así que hoy este par vuelve a ser lo que siempre debió ser, un realce ' +
      'decorativo que acompaña.',
  },
  'on-solid/accent-disabled': {
    ratio: 2.64,
    note:
      'El texto del botón primario APAGADO. WCAG exime los controles inactivos, así que no es ' +
      'un incumplimiento; se declara igual porque es el peor par que se dibuja a propósito. ' +
      'Este PR lo MEJORA: los tres modales de alta al vuelo y los tres controles del asistente ' +
      'lo tenían en 1.81, que es un botón que el usuario no puede leer mientras espera, y los ' +
      'siete usos de hoy pasan a este tono.',
  },
  'warning/warning-soft-hover': {
    ratio: 4.04,
    note:
      'El texto del chip de filtro cuando el mouse está encima. Queda a 0.46 del mínimo, y es ' +
      'anterior a este PR: el relleno de hover ya era ese tono escrito a mano. En oscuro el ' +
      'mismo par da 4.56 y pasa.',
  },
  'fg-subtle/accent-soft': {
    ratio: 4.38,
    note:
      'El texto de sugerencia dentro de la caja de notas de la cotización, que es el único de ' +
      'sus cuatro fondos donde no llega. Y no hay a dónde moverlo: el paso siguiente de la escala ' +
      'es el del texto secundario, y usarlo daría vuelta la jerarquía, que es el error que la ' +
      'revisión de este PR levantó. Además el sitio le pone opacidad a esa caja, así que el par ' +
      'que se ve de verdad es un poco mejor que este, que mide el token pleno.',
  },
}

/**
 * Las excepciones del TEMA OSCURO. Son DOS, y las dos ya fallaban en claro: **el tema oscuro no
 * estrena ni un incumplimiento**. Al revés, arregla DOS de las CUATRO que le quedan al claro.
 *
 * Eran nueve hasta que el PR del contraste claro sacó de la lista los filetes decorativos y la
 * pastilla: no porque cumplieran, sino porque no son cosas que la norma pida medir. Quedan las
 * dos que sí se miden y no llegan: el realce de la fila de una tabla, que es el peor par del
 * sistema con 1.06 y es decorativo desde que el foco tiene contorno propio, y el texto del botón
 * primario apagado, que la norma exime por ser un control inactivo.
 *
 * Los conteos de este bloque los fijan las listas congeladas de más abajo: si alguno se
 * escribe de memoria y queda viejo, esas listas siguen en verde y este texto miente. Pasó
 * con la primera versión, con cuatro números, y volvió a pasar con esta: el PR que dejó la
 * lista en dos no tocó una sola línea de este comentario, y los cinco números que decía
 * quedaron mal a la vez. Lo levantó la revisión de cierre.
 */
const EXCEPCIONES_OSCURO: Record<string, { ratio: number; note: string }> = {
  'surface-subtle/surface': {
    ratio: 1.06,
    note:
      'El realce de la fila clickeable, que acompaña al contorno de foco sin ser él. En claro da ' +
      '1.05: el tema oscuro no lo arregla ni lo empeora, y no hace falta que lo arregle, porque ' +
      'lo que señala el foco es el contorno y ese sí se mide.',
  },
  'on-solid/accent-disabled': {
    ratio: 3.89,
    note:
      'El mismo botón apagado en oscuro. Mejora respecto del claro (2.64) y sigue sin llegar; ' +
      'WCAG exime los controles inactivos. Se eligió un tono que se distingue del primario ' +
      'activo (1.74 entre los dos) para que "apagado" se siga leyendo como apagado.',
  },
}

const EXCEPCIONES: Record<Tema, Record<string, { ratio: number; note: string }>> = {
  claro: EXCEPCIONES_CLARO,
  oscuro: EXCEPCIONES_OSCURO,
}

const cssPath = join(process.cwd(), 'src', 'index.css')
const css = readFileSync(cssPath, 'utf8')
const colors = parseThemeColors(css)
/**
 * El selector del tema oscuro se escribe UNA sola vez, acá: si alguien lo cambia en el CSS,
 * esta lectura lanza y el archivo entero falla al importar, en vez de medir media paleta.
 */
export const SELECTOR_OSCURO = ":root[data-theme='dark']"
const colorsOscuro = parseThemeOverrides(css, SELECTOR_OSCURO)

/**
 * Las dos paletas, con el mismo juego de nombres. Todo lo que se mide abajo se mide en las
 * dos: un tema que no se mide es un tema donde el contraste se rompe sin que nadie lo vea.
 */
const PALETAS = { claro: colors, oscuro: colorsOscuro } as const
type Tema = keyof typeof PALETAS

const key = (pair: Pair) => `${pair.fg}/${pair.bg}`

/** El cuerpo del bloque oscuro dentro de un CSS ya compilado, para medir DENTRO y no "en algún lado". */
const bloqueOscuro = (css: string) =>
  new RegExp(SELECTOR_OSCURO.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*\\{([^{}]*)\\}').exec(css)?.[1] ?? ''

describe('declaración del tema', () => {
  /**
   * Estas guardas miden el CSS COMPILADO y no el texto del archivo, y el motivo
   * es que las tres versiones anteriores afirmaban el símbolo y no su efecto.
   * Cada arreglo movía el símbolo un paso y la vuelta siguiente lo esquivaba por
   * un camino nuevo: el modificador nombrado en un comentario, el modificador en
   * otro bloque, el tema oscuro en un archivo aparte o anidado en una línea. Y
   * en la dirección opuesta, un comentario en castellano que usara la palabra
   * del selector ponía la suite en rojo con el código intacto.
   *
   * Sobre lo compilado las dos cosas se resuelven solas: los comentarios no
   * sobreviven a la compilación, y lo que se mide es lo que se publica.
   */
  let compilado = ''
  let fuentes: { base: string; pattern: string; negated: boolean }[] = []

  beforeAll(async () => {
    const salida = await compileGlobalCss(process.cwd())
    compilado = salida.css
    fuentes = salida.fuentes
  })

  it('los tokens llegan al CSS que se publica, con su valor', async () => {
    // Sin `static` Tailwind descarta toda variable que ninguna utilidad use, y
    // `inline` hornea el valor en cada utilidad en vez de emitir la variable.
    // Las dos dejan el CSS sin un solo token y las dos compilan sin error.
    // Pares nombre->valor y no solo nombres: con un conjunto de nombres, un
    // `:root` al final del archivo publica otro color y la suite sigue midiendo
    // el declarado arriba. Medido: la página quedaba blanca, indistinguible de
    // las tarjetas, con todo en verde.
    // Alfabeto amplio a propósito: si el regex solo viera kebab-minúscula, un
    // token con mayúscula o guion bajo sería invisible de LOS DOS lados de la
    // comparación y la igualdad se cumpliría al vacío. Medido: un
    // `--color-Intruso` pasaba en verde y se publicaba.
    // Con DOS temas ya no alcanza con recorrer el archivo entero: los dos bloques declaran
    // los mismos nombres con valores distintos, así que un mapa plano se queda con el
    // último y la comparación mediría un solo tema. Se lee cada bloque por separado, con
    // la misma función que lee el CSS fuente.
    const publicadoEnBloque = (recorte: string) =>
      new Map(
        [...recorte.matchAll(/--color-([A-Za-z0-9_-]+)\s*:\s*([^;}]+)[;}]/g)].map((m) => [
          m[1],
          m[2].trim(),
        ]),
      )
    const bloqueOscuroCss = new RegExp(
      SELECTOR_OSCURO.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*\\{([^{}]*)\\}',
    ).exec(compilado)
    expect(bloqueOscuroCss, 'el bloque del tema oscuro no llegó al CSS publicado').not.toBeNull()
    const enElCssOscuro = publicadoEnBloque(bloqueOscuroCss![1])
    // Lo claro se mide sobre el CSS SIN el bloque oscuro, para que el segundo no le pise
    // los valores al primero.
    const enElCss = publicadoEnBloque(compilado.replace(bloqueOscuroCss![0], ''))

    // El CSS trae además la paleta de Tailwind, que el escáner arrastra al
    // compilar con las clases reales. Lo que se afirma es que CADA token del
    // tema esté publicado y con su valor, no que sean los únicos.
    for (const [tema, paleta, publicado] of [
      ['claro', colors, enElCss],
      ['oscuro', colorsOscuro, enElCssOscuro],
    ] as const) {
      const publicados = Object.keys(paleta)
        .map((n) => `${n}: ${publicado.get(n)}`)
        .sort()
      expect(publicados, `el tema ${tema} no llegó completo al CSS publicado`).toEqual(
        Object.entries(paleta)
          .map(([n, v]) => `${n}: ${v}`)
          .sort(),
      )
    }

    // Y que la variable se REFERENCIE en la utilidad en vez de hornearse: el
    // modificador `inline` publica los 45 tokens igual y mete el literal en cada
    // clase, con lo que redefinir el tema no cambiaría un pixel. Es el fallo
    // silencioso que este archivo declara como el peligroso, y el conjunto de
    // nombres no lo veía.
    const { css: conUtilidad } = await compileGlobalCss(process.cwd(), ['bg-canvas'])
    expect(conUtilidad).toMatch(/\.bg-canvas\s*\{\s*background-color:\s*var\(--color-canvas\)/)
  })

  /**
   * Lo que las otras guardas miden tiene que ser CSS COMPILADO y no el archivo
   * fuente. Sin esto, un helper que devolviera el texto crudo las dejaba a las
   * cuatro en verde, que es justo la regresión que este bloque vino a cerrar.
   * Las dos señales son complementarias: las directivas no sobreviven a la
   * compilación, y el preflight solo existe del otro lado.
   */
  it('lo que se mide es CSS compilado, no el archivo fuente', () => {
    expect(compilado).not.toMatch(/@theme|@source|@import/)
    expect(compilado).toMatch(/-webkit-text-size-adjust/)
  })

  /**
   * Esta guarda nació al revés: exigía que NO hubiera un segundo tema, para forzar que el
   * PR del modo oscuro extendiera la medición en vez de dejar la suite en verde midiendo la
   * mitad. Cumplió: falló el día que apareció el bloque oscuro. Ahora exige lo contrario,
   * que los temas publicados sean EXACTAMENTE dos y que los dos declaren el mismo juego de
   * nombres, que es la forma de que un tema no se quede corto sin que nadie lo note.
   */
  it('se publican exactamente dos temas, y los dos declaran los mismos tokens', () => {
    const bloquesConTokens = [...compilado.matchAll(/\{[^{}]*--color-[a-z0-9-]+\s*:/g)]
    expect(
      bloquesConTokens,
      'un tercer lugar que declare tokens es un tercer tema, se llame como se llame',
    ).toHaveLength(2)
    expect(Object.keys(colorsOscuro).sort()).toEqual(Object.keys(colors).sort())
    // Sin esto el navegador pinta con el tema claro lo que no controlamos: las barras de
    // desplazamiento, el autocompletado y los selectores nativos de fecha, que esta
    // aplicación usa. Quedan claros sobre fondo oscuro y se leen como un defecto.
    expect(bloqueOscuro(compilado), 'el `color-scheme` va DENTRO del bloque oscuro: suelto se lo come también el tema claro y deja las barras y los selectores de fecha oscuros sobre página blanca').toMatch(
      /color-scheme:\s*dark/,
    )
    // Y la mitad de la guarda vieja que la reescritura había perdido: **ninguna variante
    // `dark:` ni ninguna consulta de preferencia del sistema**. Las dos siguen al sistema
    // operativo y NO al atributo, o sea que le pasan por encima a la elección del usuario:
    // quien tenga el sistema en oscuro y elija claro se llevaría los tokens claros con las
    // utilidades oscuras encima. Lo levantó la revisión de este PR.
    expect(
      compilado,
      'el tema se maneja por atributo: una variante `dark:` o una media query sigue al sistema y pisa la elección del usuario',
    ).not.toMatch(/prefers-color-scheme|\.dark\\:/)
  })

  /**
   * El CSS global entra al bundle por un import de efecto, sin símbolo, así que
   * cualquier herramienta que pode imports "no usados" lo saca. Medido: sin esa
   * línea el build sale exit 0, sin warning, y `dist/` queda sin ninguna hoja de
   * estilos. No es que falten los tokens: no hay CSS.
   */
  /**
   * `index.css` tiene que ser la ÚNICA hoja del árbol. El compilador de esta
   * prueba solo la ve a ella: una segunda hoja importada desde un módulo TS
   * (que es exactamente cómo entra `index.css`) se publica sin que ninguna de
   * estas guardas la mire. Medido: un `theme-dark.css` importado desde
   * `main.tsx` publicaba el tema oscuro entero con la suite en verde.
   *
   * Si algún día hace falta otra hoja, la salida es `@import`arla desde
   * `index.css`, que sí entra por este compilador, o medirla aparte.
   */
  it('el CSS global es la única hoja del árbol', () => {
    const hojas = readdirSync(join(process.cwd(), 'src'), { recursive: true })
      .map(String)
      .filter((f) => f.endsWith('.css'))
    expect(hojas, 'una hoja nueva no la compila esta prueba').toEqual(['index.css'])
  })

  it('el CSS global entra al bundle', () => {
    const main = readFileSync(join(process.cwd(), 'src', 'main.tsx'), 'utf8')
    // Estructural, y conviene decirlo: afirma que la línea esté, no que el CSS
    // llegue al bundle. Moverla a otro módulo que main importe también funciona
    // y esta guarda la daría por rota. Tolera comillas y punto y coma para no
    // romperse con un formateador.
    expect(main).toMatch(/^import\s+['"]\.\/index\.css['"];?$/m)
  })

  /**
   * El hermano del anterior, y por el mismo motivo: **el cableado del proveedor del tema no
   * lo afirma nadie**. Cada prueba que lo necesita se lo pone a sí misma, así que sacarlo del
   * arranque deja la suite entera en verde y la aplicación en blanco: el pie de la barra
   * llama a `useTheme`, que lanza fuera del proveedor, y ese pie se pinta en TODA pantalla
   * autenticada. Medido durante la revisión de este PR: sin el proveedor en el arranque,
   * ocho archivos y 231 casos seguían pasando.
   *
   * Estructural igual que el del CSS, con la misma advertencia: afirma que las líneas estén.
   */
  it('el proveedor del tema envuelve la aplicación de verdad', () => {
    const main = readFileSync(join(process.cwd(), 'src', 'main.tsx'), 'utf8')
    // Sin anclas de línea: un formateador que parta el import en varias líneas no cambia nada
    // y la versión anclada ponía la suite en rojo con el código intacto.
    expect(main).toMatch(
      /import\s*\{[^}]*\bThemeProvider\b[^}]*\}\s*from\s*['"]\.\/shared\/ui\/theme\/ThemeContext['"]/,
    )
    expect(main).toMatch(/<ThemeProvider>/)
    expect(main).toMatch(/<\/ThemeProvider>/)

    // Y lo que las etiquetas solas NO dicen: qué queda adentro. Dejar el proveedor envolviendo
    // cualquier cosa y la aplicación afuera compila y deja la suite entera en verde, y da la
    // misma pantalla en blanco que borrarlo, porque el pie de la barra llama al contexto y
    // lanza fuera de él. Lo midió la revisión de este PR.
    const adentro = main.slice(
      main.indexOf('<ThemeProvider>') + '<ThemeProvider>'.length,
      main.indexOf('</ThemeProvider>'),
    )
    expect(adentro, 'la aplicación tiene que quedar DENTRO del proveedor').toContain(
      '<RouterProvider',
    )
    // Y las notificaciones también: `sonner` trae su propio tema y no mira el atributo.
    expect(adentro, 'las notificaciones también van adentro').toContain('<ThemedToaster')
  })

  /**
   * La directiva que saca la carpeta del tema del escaneo, afirmada sobre la
   * lectura que hace TAILWIND (`compile()` devuelve sus fuentes ya parseadas) y
   * no sobre un regex nuestro.
   *
   * ALCANCE, con precisión: esto afirma qué patrones de exclusión hay y cuáles
   * son, no qué archivos terminan escaneados. Lo segundo se afirma aparte, en la
   * guarda de los colores escritos a mano, que toma su corpus del escáner y
   * exige que esta carpeta no aparezca en él: ahí se mide el efecto y acá la
   * declaración. Una versión anterior de este comentario decía que el efecto no
   * se podía medir en una prueba unitaria: era falso, y se corrige acá.
   *
   * Lo que sí ataja como está: que la directiva desaparezca, que se recorte a
   * una extensión o a un nivel (los dos modos de falla que ya ocurrieron), y que
   * aparezca una de más, que saca reglas del bundle sin tocar esta línea.
   */
  it('la exclusión del escáner es exactamente una y cubre toda la carpeta del tema', () => {
    // Se afirman TODAS las fuentes, no solo las negadas: un `@source` que
    // ensanche el escaneo también cambia lo que se publica y era invisible.
    // Y la base junto con el patrón, porque un patrón correcto con la base
    // equivocada excluye otra carpeta sin que nadie se entere.
    expect(fuentes.map((f) => `${f.negated ? '!' : ''}${f.pattern}`)).toEqual([
      '!./shared/theme/**',
    ])
    expect(fuentes[0].base).toBe(join(process.cwd(), 'src'))

    // Y que la carpeta que excluye no tenga código de pantalla: lo que viva acá
    // no publica sus clases, así que un componente quedaría sin estilo y el
    // build no diría nada.
    const carpeta = join(process.cwd(), 'src', 'shared', 'theme')
    // Se afirma el contenido EXACTO de la carpeta, y no que no haya `.tsx`: el
    // patrón de la casa para guardar nombres de clase es un `.ts` sin JSX
    // (`shared/ui/buttonClasses.ts`, `shared/utils/cn.ts`), que es justo lo que
    // los PRs de extracción van a querer poner acá. Medido: un `themeStyles.ts`
    // con literales de clase dejaba dos de tres sin publicar, con el build en
    // exit 0 y la suite en verde.
    const contenido = readdirSync(carpeta, { recursive: true }).map(String).sort()
    expect(contenido, 'acá no se publican clases: cualquier archivo nuevo tiene que ser de prueba o de apoyo').toEqual([
      'compileCss.test.ts',
      'compileCss.ts',
      'contrast.test.ts',
      'contrast.ts',
      'focus-declarado.test.ts',
      'no-raw-colors.test.ts',
      'palette.test.ts',
      'palette.ts',
      'readTokens.test.ts',
      'readTokens.ts',
      'theme-contrast.test.ts',
    ])

    // Y que nadie de afuera importe la carpeta: sus módulos solo corren en Node
    // y uno arrastra Tailwind entero. Medido: importarlo desde una pantalla suma
    // ~10 kB de JS y 86 selectores muertos, con lint, typecheck y build en 0.
    const raiz = join(process.cwd(), 'src')
    const importadores = readdirSync(raiz, { recursive: true })
      .map(String)
      .filter((f) => /\.tsx?$/.test(f) && !f.startsWith('shared/theme/'))
      .filter((f) => /from\s+['"][^'"]*shared\/theme\//.test(readFileSync(join(raiz, f), 'utf8')))
    expect(importadores, 'estos módulos solo corren en Node').toEqual([])
  })
})

describe('tokens del tema', () => {
  /**
   * Los valores, congelados. La prueba de contraste sola NO los protege: solo ve
   * un cambio cuando cruza un umbral o toca una excepción fijada. Medido: pasar
   * el fondo de página a blanco (con lo que la página y las tarjetas dejan de
   * distinguirse, o sea la aplicación entera se aplana) deja la suite en verde,
   * y lo mismo cambiar el acento de azul a índigo.
   *
   * El valor de cada línea sale del color de paleta que el CSS declara al lado,
   * así que esto es transcripción, no una segunda fuente de verdad: si alguien
   * cambia un valor a propósito, cambia las dos líneas y el diff lo muestra.
   */
  it('los valores de los tokens son exactamente estos', () => {
    expect(colors).toEqual({
      'canvas': '#f8fafc', // slate-50
      'surface': '#ffffff', // white
      'surface-subtle': '#f8fafc', // slate-50
      'surface-muted': '#f1f5f9', // slate-100
      'fg': '#0f172b', // slate-900
      'fg-body': '#314158', // slate-700
      'fg-muted': '#45556c', // slate-600
      'fg-subtle': '#62748e', // slate-500
      'border': '#e2e8f0', // slate-200
      'border-strong': '#62748e', // slate-500
      'accent': '#155dfc', // blue-600
      'accent-hover': '#1447e6', // blue-700
      'accent-soft': '#eff6ff', // blue-50
      'accent-soft-strong': '#dbeafe', // blue-100
      'on-solid': '#ffffff', // white
      'accent-disabled': '#51a2ff', // blue-400
      'focus': '#2b7fff', // blue-500
      'danger': '#e7000b', // red-600
      'danger-hover': '#c10007', // red-700
      'danger-soft': '#fef2f2', // red-50
      'danger-soft-strong': '#ffe2e2', // red-100
      'danger-fg': '#c10007', // red-700
      'accent-border': '#8ec5ff', // blue-300
      'danger-border': '#ffc9c9', // red-200
      'danger-border-strong': '#fb2c36', // red-500
      'warning': '#bb4d00', // amber-700
      'warning-soft': '#fffbeb', // amber-50
      'warning-soft-strong': '#fef3c6', // amber-100
      'warning-fg': '#973c00', // amber-800
      'success-border': '#a4f4cf', // emerald-200
      'overlay': '#0f172b', // slate-900
      'elevation': '#e2e8f0', // slate-200
      'warning-soft-hover': '#fee685', // amber-200
      'trace': '#cad5e2', // slate-300
      'warning-border': '#fee685', // amber-200
      'warning-border-strong': '#e17100', // amber-600
      'progress-soft': '#ede9fe', // violet-100
      'progress-fg': '#7008e7', // violet-700
      'transition-soft': '#cbfbf1', // teal-100
      'transition': '#00786f', // teal-700
      'transition-hover': '#005f5a', // teal-800
      'transition-fg': '#00786f', // teal-700
      'success': '#007a55', // emerald-700
      'success-soft': '#d0fae5', // emerald-100
      'success-fg': '#007a55', // emerald-700
    })
  })

  /**
   * La segunda fuente de verdad, y el motivo por el que el literal de arriba no
   * alcanza solo: ese literal y el CSS son el mismo dato escrito dos veces, así
   * que cambiar los dos a la vez pasa en verde. Medido antes de escribir esto:
   * pasar un token del valor de Tailwind 4 al de Tailwind 3, en los dos lados,
   * no lo mataba ninguna prueba, y el comentario de paleta quedaba mintiendo.
   *
   * Acá el valor se recalcula desde los `oklch()` del Tailwind INSTALADO, o sea
   * la versión que fija el lockfile, usando el nombre que cada línea del CSS
   * declara en su comentario. Los 45 dan byte a byte: no hay tolerancia ni
   * aproximación. Si un upgrade de Tailwind mueve un color de la paleta, esto lo
   * dice, que es la otra mitad de lo que este caso protege.
   */
  /**
   * Y los del tema OSCURO, que no tenían red. El comentario de arriba explica por qué el
   * contraste solo no alcanza: solo ve un cambio cuando cruza un umbral o toca una excepción
   * fijada. Medido durante la revisión de este PR: **poner el velo del modal en blanco, o el
   * acento oscuro en verde, dejaba TODO este archivo en verde**. Los tokens que no entran
   * en ningún par (los de `SIN_PAR`) no tenían absolutamente nada que los mirara.
   *
   * Estos valores NO se recalculan desde la paleta de Tailwind, como los del claro: son
   * tonos propios elegidos sobre azul marino, así que este literal es toda su red.
   */
  it('los valores del tema oscuro son exactamente estos', () => {
    expect(colorsOscuro).toEqual({
      'canvas': '#0b1626',
      'surface': '#132238',
      'surface-subtle': '#16273f',
      'surface-muted': '#1b2f4a',
      'fg': '#eef3fa',
      'fg-body': '#cfdbeb',
      'fg-muted': '#a4b6d0',
      'fg-subtle': '#8195b4',
      'border': '#263a58',
      'border-strong': '#526f9c',
      'accent': '#5b9bff',
      'accent-hover': '#7cb0ff',
      'accent-soft': '#12294a',
      'accent-soft-strong': '#0e2036',
      'accent-border': '#1e4976',
      'on-solid': '#08121f',
      'focus': '#dbeafe',
      'danger': '#f87171',
      'danger-hover': '#fca5a5',
      'danger-soft': '#3a1620',
      'danger-soft-strong': '#310c10',
      'danger-fg': '#fca5a5',
      'danger-border': '#7f2436',
      'danger-border-strong': '#d4667c',
      'warning': '#fbbf24',
      'warning-soft': '#3a2a10',
      'warning-soft-strong': '#4a3614',
      'warning-fg': '#fcd34d',
      'warning-border': '#6b4f18',
      'warning-border-strong': '#927224',
      'progress-soft': '#251f3a',
      'progress-fg': '#c4b4ff',
      'transition-soft': '#00312d',
      'transition': '#46ecd5',
      'transition-hover': '#96f7e4',
      'transition-fg': '#46ecd5',
      'success': '#34d399',
      'success-soft': '#0d2f27',
      'success-fg': '#6ee7b7',
      'success-border': '#1c5c46',
      'accent-disabled': '#4c74a5',
      'overlay': '#000000',
      'elevation': '#000000',
      'warning-soft-hover': '#6b4f1d',
      'trace': '#44577a',
    })
  })

  it('cada token vale lo que la paleta de Tailwind dice que vale', () => {
    const paleta = readTailwindPalette(
      join(process.cwd(), 'node_modules', 'tailwindcss', 'theme.css'),
    )
    const declarados = [
      ...css.matchAll(/--color-([a-z0-9-]+):\s*(#[0-9a-f]{6});\s*\/\* *([a-z]+-?[0-9]*) *\*\//g),
    ]
    expect(declarados, 'cada token tiene que nombrar su color de paleta en un comentario').toHaveLength(
      Object.keys(colors).length,
    )
    for (const [, token, valor, nombrePaleta] of declarados) {
      expect(
        valor,
        `${token} dice ser ${nombrePaleta}, que en el Tailwind instalado vale ${paleta[nombrePaleta]}`,
      ).toBe(paleta[nombrePaleta])
    }
  })

  it('cada par de la lista usa tokens que existen', () => {
    const faltantes = PAIRS.flatMap((pair) =>
      [pair.fg, pair.bg].filter((token) => !(token in colors)),
    )
    expect(faltantes).toEqual([])
  })

  /**
   * La guarda de cobertura, y el motivo por el que no se cuenta la cantidad de
   * tokens: contarla es un badén, no un guarda. Medido: agregar un token con
   * contraste pésimo y subir el número de uno hace pasar la suite entera. Esto,
   * en cambio, obliga a que todo token nuevo entre en un par o se justifique.
   */
  it('todo token está en algún par, salvo los justificados', () => {
    const medidos = new Set(PAIRS.flatMap((pair) => [pair.fg, pair.bg]))
    const sinPar = Object.keys(colors)
      .filter((token) => !medidos.has(token))
      .sort()
    expect(sinPar).toEqual(Object.keys(SIN_PAR).sort())
  })

  /**
   * El conjunto de pares, congelado. Contar cuántos hay no alcanza: borrar un par
   * y duplicar otro deja la cuenta igual, no toca el número del diff y saca ese
   * par de la medición sin dejar dónde mirar (medido). Fijar las claves obliga a
   * que agregar o quitar un par sea un cambio visible en la revisión.
   */
  it('el conjunto de pares medidos es exactamente este', () => {
    expect(PAIRS.map(key).sort()).toEqual([
      'accent-hover/accent-soft',
      'accent-hover/accent-soft-strong',
      'accent-hover/canvas',
      'accent-hover/surface',
      'accent-hover/surface-muted',
      'accent-hover/surface-subtle',
      'accent/accent-soft',
      'accent/canvas',
      'accent/surface',
      'border-strong/canvas',
      'border-strong/surface',
      'danger-border-strong/canvas',
      'danger-border-strong/danger-soft',
      'danger-border-strong/surface',
      'danger-fg/danger-soft',
      'danger-fg/danger-soft-strong',
      'danger-fg/surface',
      'danger/canvas',
      'danger/surface',
      'fg-body/accent-soft',
      'fg-body/canvas',
      'fg-body/surface',
      'fg-body/surface-muted',
      'fg-body/surface-subtle',
      'fg-muted/accent-soft',
      'fg-muted/canvas',
      'fg-muted/surface',
      'fg-muted/surface-muted',
      'fg-muted/surface-subtle',
      'fg-muted/warning-soft',
      'fg-subtle/accent-soft',
      'fg-subtle/canvas',
      'fg-subtle/surface',
      'fg-subtle/surface-subtle',
      'fg/accent-soft',
      'fg/canvas',
      'fg/danger-soft',
      'fg/success-soft',
      'fg/surface',
      'fg/surface-muted',
      'fg/surface-subtle',
      'fg/warning-soft',
      'focus/accent-soft',
      'focus/canvas',
      'focus/danger-soft',
      'focus/surface',
      'focus/surface-muted',
      'focus/surface-subtle',
      'focus/warning-soft',
      'focus/warning-soft-strong',
      'on-solid/accent',
      'on-solid/accent-disabled',
      'on-solid/accent-hover',
      'on-solid/danger',
      'on-solid/danger-hover',
      'on-solid/success',
      'on-solid/transition',
      'on-solid/transition-hover',
      'progress-fg/progress-soft',
      'success-fg/success-soft',
      'success-fg/surface',
      'success/surface',
      'surface-subtle/surface',
      'transition-fg/transition-soft',
      'warning-border-strong/surface',
      'warning-border-strong/warning-soft',
      'warning-fg/surface',
      'warning-fg/warning-soft',
      'warning-fg/warning-soft-strong',
      'warning/surface',
      'warning/warning-soft',
      'warning/warning-soft-hover',
      'warning/warning-soft-strong',
    ])
  })

  /**
   * La autodestrucción de cada excepción solo dispara cuando alguien ARREGLA un
   * color. No había nada que disparara cuando alguien lo ROMPE: medido, aclarar
   * el rojo un paso rompe tres pares, y agregar sus tres entradas con el cociente
   * real deja la suite en verde. Tres regresiones de AA absorbidas sin señal.
   * Congelar el conjunto convierte eso en un cambio que hay que defender.
   */
  it('el conjunto de excepciones del tema claro es exactamente este', () => {
    expect(Object.keys(EXCEPCIONES.claro).sort()).toEqual([
      'fg-subtle/accent-soft',
      'on-solid/accent-disabled',
      'surface-subtle/surface',
      'warning/warning-soft-hover',
    ])
  })

  /**
   * Y el del oscuro, que es un conjunto DISTINTO y por eso se congela aparte: si fueran la
   * misma lista, arreglar un par en un tema y romperlo en el otro se compensaría solo.
   */
  it('el conjunto de excepciones del tema oscuro es exactamente este', () => {
    expect(Object.keys(EXCEPCIONES.oscuro).sort()).toEqual([
      'on-solid/accent-disabled',
      'surface-subtle/surface',
    ])
  })

  /**
   * Las dos del tema claro que el oscuro ARREGLA. Se afirman por nombre y no por número
   * para que nadie las pierda de vista: si mañana una vuelve a fallar en oscuro, este caso
   * lo dice, y si alguien la arregla también en claro, hay que sacarla de las dos listas.
   */
  it('el tema oscuro arregla exactamente estas dos del claro', () => {
    const arregladas = Object.keys(EXCEPCIONES.claro)
      .filter((k) => !(k in EXCEPCIONES.oscuro))
      .sort()
    expect(arregladas).toEqual([
      'fg-subtle/accent-soft',
      'warning/warning-soft-hover',
    ])
  })

  it('el tema oscuro no estrena ninguna excepción propia', () => {
    const propias = Object.keys(EXCEPCIONES.oscuro).filter((k) => !(k in EXCEPCIONES.claro))
    expect(
      propias,
      'una excepción que solo existe en oscuro es un incumplimiento que estrena este tema',
    ).toEqual([])
  })

  it('no quedan excepciones declaradas sobre pares que ya no existen', () => {
    const declarados = new Set(PAIRS.map(key))
    const huerfanas = Object.entries(EXCEPCIONES).flatMap(([tema, mapa]) =>
      Object.keys(mapa).filter((k) => !declarados.has(k)).map((k) => `${tema}: ${k}`),
    )
    expect(huerfanas).toEqual([])
  })
})

/**
 * Los umbrales se afirman contra la norma, no solo se usan. Medido: bajar
 * AA_TEXT de 4.5 a 4.4, o degradar un par de texto al mínimo de 1.4.11, deja la
 * suite en verde. Aflojar el listón es el camino que un apuro toma de verdad, y
 * es el que convierte esta prueba en decorativa.
 */
describe('los umbrales son los de la norma', () => {
  it('texto normal exige 4.5:1 (WCAG 2.1 AA, 1.4.3)', () => {
    expect(AA_TEXT).toBe(4.5)
  })

  it('elemento no textual exige 3:1 (WCAG 2.1 AA, 1.4.11)', () => {
    expect(AA_NON_TEXT).toBe(3)
  })

  it('todo par se mide contra uno de los dos umbrales, y no contra un número suelto', () => {
    const sueltos = PAIRS.filter((pair) => pair.min !== AA_TEXT && pair.min !== AA_NON_TEXT)
    expect(sueltos.map(key)).toEqual([])
  })

  it('solo el foco y los bordes de control se miden como elemento no textual', () => {
    const noTextuales = PAIRS.filter((pair) => pair.min === AA_NON_TEXT).map(key).sort()
    expect(noTextuales).toEqual(
      [
      'accent/canvas',
      'border-strong/canvas',
      'border-strong/surface',
      'danger-border-strong/canvas',
      'danger-border-strong/danger-soft',
      'danger-border-strong/surface',
      'focus/accent-soft',
      'focus/canvas',
      'focus/danger-soft',
      'focus/surface',
      'focus/surface-muted',
      'focus/surface-subtle',
      'focus/warning-soft',
      'focus/warning-soft-strong',
      'surface-subtle/surface',
      'warning-border-strong/surface',
      'warning-border-strong/warning-soft',
    ].sort(),
    )
  })
})

/**
 * La misma tabla de pares, medida en los DOS temas. Está escrito como un bucle sobre las
 * paletas y no duplicado a mano por un motivo concreto: dos copias de esta lista se
 * desincronizan en el primer par que alguien agregue, y el tema que quede sin ese par va a
 * pasar en verde midiendo de menos. Lo único que difiere por tema son las excepciones.
 */
describe.each(Object.keys(PALETAS) as Tema[])('contraste del tema %s', (tema) => {
  const paleta = PALETAS[tema]
  const excepciones = EXCEPCIONES[tema]
  const conocidos = PAIRS.filter((pair) => key(pair) in excepciones)
  const exigidos = PAIRS.filter((pair) => !(key(pair) in excepciones))

  it.each(exigidos.map((pair) => [key(pair), pair] as const))(
    '%s llega al mínimo',
    (_name, pair) => {
      const ratio = contrastRatio(paleta[pair.fg], paleta[pair.bg])
      expect(
        ratio,
        `${pair.fg} sobre ${pair.bg} (${pair.what}) da ${ratio.toFixed(2)}:1 en el tema ${tema} ` +
          `y necesita ${pair.min}:1`,
      ).toBeGreaterThanOrEqual(pair.min)
    },
  )

  it.each(conocidos.map((pair) => [key(pair), pair] as const))(
    '%s sigue siendo una excepción conocida',
    (name, pair) => {
      const esperado = excepciones[name]
      const ratio = contrastRatio(paleta[pair.fg], paleta[pair.bg])
      expect(
        Number(ratio.toFixed(2)),
        `${name} cambió de ${esperado.ratio}:1 a ${ratio.toFixed(2)}:1 en el tema ${tema}. Si lo ` +
          `arreglaste, borrá su entrada: la excepción ya no corresponde.`,
      ).toBe(esperado.ratio)
      expect(
        ratio,
        `${name} ya llega a ${pair.min}:1 en el tema ${tema}. Borrá su entrada.`,
      ).toBeLessThan(pair.min)
    },
  )
})

/**
 * El velo del modal, que es el único par que ningún par entre tokens puede medir: el sitio le
 * pone la opacidad, así que lo que el ojo ve es una MEZCLA y no un token. Por eso `overlay`
 * está en la lista de los que no se miden, y su justificación cita tres números.
 *
 * Hasta la segunda ronda de revisión esos tres números eran prosa suelta, y estaban mal en los
 * tres archivos donde aparecían, cada uno de una forma distinta: se habían calculado a una
 * opacidad que el componente ya no aplicaba. Acá se calculan, y de las dos fuentes que pueden
 * cambiar: la opacidad sale del propio componente y los colores del CSS.
 *
 * Se compone sobre el LIENZO, que es lo que hay detrás de un modal abierto.
 */
describe('el velo del modal', () => {
  const modal = readFileSync(join(process.cwd(), 'src', 'shared', 'ui', 'Modal.tsx'), 'utf8')
  const opacidad = Number(/bg-overlay\/(\d{1,3})\b/.exec(modal)?.[1]) / 100

  /** El velo sobre su fondo, en el espacio en el que el navegador mezcla: sRGB sin linealizar. */
  function componer(frente: string, fondo: string, alfa: number): string {
    const f = parseHex(frente)
    const b = parseHex(fondo)
    return toHex([0, 1, 2].map((i) => Math.round(f[i] * alfa + b[i] * (1 - alfa))) as unknown as ReturnType<typeof parseHex>)
  }

  it('la opacidad se pudo leer del componente', () => {
    expect(opacidad, 'sin opacidad legible los números de abajo no miden nada').toBeGreaterThan(0)
  })

  it('en claro la tarjeta salta sola contra el velo', () => {
    const velo = componer(colors.overlay, colors.canvas, opacidad)
    expect(Number(contrastRatio(colors.surface, velo).toFixed(2))).toBe(4.83)
  })

  /**
   * Y en oscuro no: el velo apaga un fondo que YA era oscuro, así que la tarjeta queda casi
   * pegada a él. Lo que la recorta es su filete, y por eso el panel lleva uno. Este par de
   * aserciones es la justificación de ese borde, medida: si algún día la tarjeta sola
   * alcanzara, el borde pasaría a ser decorativo y esto lo diría.
   */
  it('en oscuro la tarjeta no alcanza y el filete es el que separa', () => {
    const velo = componer(colorsOscuro.overlay, colorsOscuro.canvas, opacidad)
    const tarjeta = Number(contrastRatio(colorsOscuro.surface, velo).toFixed(2))
    const filete = Number(contrastRatio(colorsOscuro.border, velo).toFixed(2))
    expect(tarjeta).toBe(1.25)
    expect(filete).toBe(1.74)
    expect(filete, 'si la tarjeta separara más que su filete, el filete sobraría').toBeGreaterThan(
      tarjeta,
    )
  })

  it('el panel lleva el filete que esos números justifican', () => {
    expect(
      modal,
      'sin filete, en oscuro nada separa la tarjeta del velo',
    ).toMatch(/border\s+border-border/)
  })
})

/**
 * El color de la barra del navegador en el móvil. Vive en una etiqueta del documento, fuera del
 * alcance de cualquier hoja de estilos, así que el script del `head` lo lleva escrito a mano: es
 * la única forma de ponerlo antes de que exista un módulo. Dos valores escritos a mano son dos
 * valores que se separan del tema, y por eso se atan acá.
 */
describe('el color de la barra del navegador', () => {
  const html = readFileSync(join(process.cwd(), 'index.html'), 'utf8')

  it('los dos literales del script son el fondo de página de cada tema', () => {
    const escritos = [...html.matchAll(/tema === 'dark' \? '(#[0-9a-f]{6})' : '(#[0-9a-f]{6})'/g)]
    expect(escritos, 'no se encontró la línea que escribe el color de la barra').toHaveLength(1)
    const [, oscuro, claro] = escritos[0]
    expect(oscuro).toBe(colorsOscuro.canvas)
    expect(claro).toBe(colors.canvas)
  })

  it('la etiqueta existe en el documento y arranca en el tema claro', () => {
    const inicial = /<meta name="theme-color" content="(#[0-9a-f]{6})" \/>/.exec(html)
    expect(inicial, 'sin la etiqueta, el script no tiene qué escribir').not.toBeNull()
    expect(inicial![1]).toBe(colors.canvas)
  })
})

/**
 * El modo de alto contraste del sistema, que es el único estado que la aplicación no controla.
 * Ahí el navegador borra las sombras, y con ellas todo anillo de foco; el `outline-none` que cada
 * control declara para no ver dos marcas sobrevive. Sin una regla que devuelva un contorno, los
 * controles quedan sin ninguna señal justo en el modo que existe para que se vean.
 *
 * No hay forma de afirmarlo desde una pantalla: la suite corre con el CSS apagado y `forced-colors`
 * lo decide el sistema operativo. Lo que sí se puede afirmar es que la regla llegue al CSS
 * publicado, que es lo que se perdería si alguien la borra.
 */
describe('el modo de alto contraste', () => {
  it('el CSS publicado devuelve un contorno cuando el sistema borra las sombras', async () => {
    const { css } = await compileGlobalCss(process.cwd())
    // La consulta ENTERA, no la palabra: una mutación que la dejaba en `forced-colors: none`
    // sobrevivía a una versión anterior de esta prueba, y ese es justo el modo en el que la regla
    // NO tiene que aplicar. La medió la tabla de mutaciones.
    const i = css.indexOf('@media (forced-colors: active)')
    expect(i, 'sin esta regla, en alto contraste no queda ninguna señal de foco').toBeGreaterThan(-1)
    const regla = css.slice(i, i + 160)
    expect(regla).toMatch(/:focus-visible/)
    expect(regla, 'el contorno tiene que declarar un color del sistema, no un token').toMatch(
      /outline:\s*2px solid CanvasText/,
    )
  })
})

/**
 * La pastilla informativa contra la fila que la contiene.
 *
 * No es un mínimo de la norma y por eso no está entre los pares: lo que comunica es la palabra
 * que la pastilla lleva escrita, no su relleno. Es una invariante de DISEÑO, y existe porque ya
 * se rompió una vez: un barrido mandó los dos azules suaves al mismo token y la pastilla, que se
 * dibuja dentro de una fila del mismo tono, pasó de separarse 1.12 a 1.00, o sea a desaparecer.
 * De ahí salió el token propio.
 *
 * Hasta este PR eso lo cuidaba un par con umbral de norma, que era la herramienta equivocada: el
 * par se declaraba incumplimiento cuando no lo es. Al sacarlo, la separación se quedó sin nada que
 * la mirara, y la guarda de cobertura no puede verlo porque el token sigue apareciendo en otro par.
 * Lo levantó la revisión. Acá se afirma lo que de verdad se quiere: que la separación no se pierda.
 */
describe('la pastilla informativa no se funde con su fila', () => {
  it('conserva la separación por la que su token existe', () => {
    expect(
      Number(contrastRatio(colors['accent-soft-strong'], colors['accent-soft']).toFixed(2)),
      'si los dos azules suaves se juntan, la pastilla desaparece dentro de la fila',
    ).toBe(1.12)
  })

  it('y en el tema oscuro también, que tiene su propio par de tonos', () => {
    expect(
      Number(contrastRatio(colorsOscuro['accent-soft-strong'], colorsOscuro['accent-soft']).toFixed(2)),
    ).toBeGreaterThan(1)
  })
})

