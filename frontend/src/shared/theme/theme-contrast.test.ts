import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { beforeAll, describe, expect, it } from 'vitest'
import { compileGlobalCss } from './compileCss'
import { contrastRatio } from './contrast'
import { readTailwindPalette } from './palette'
import { parseThemeColors } from './readTokens'

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
 * esta prueba mide los pares ENTRE TOKENS. No mide, ni puede, los colores
 * crudos que todavía viven sueltos en los componentes, y hay incumplimientos
 * ahí: el paso ya completado del asistente pinta blanco sobre un verde medio (2.47:1)
 * y la tarjeta de ítem usa un rojo y un verde de un tono más claro que el del
 * token (3.81:1 y 3.65:1). Los levanta el PR de barrido, no este archivo.
 *
 * Tampoco mide que un par declarado sea el que la pantalla usa de verdad. Si
 * mañana una pantalla combina dos tokens que acá no están juntos, nadie lo va a
 * medir: LA LISTA HAY QUE MANTENERLA. La guarda de cobertura de más abajo lo
 * fuerza a medias, exigiendo que todo token aparezca al menos una vez.
 *
 * Ojo al escribir comentarios acá: Tailwind escanea este archivo buscando
 * nombres de clase, así que nombrar una utilidad del tema en una frase la crea
 * de verdad en el CSS de producción. Medido: una primera versión de este
 * comentario que nombraba dos utilidades las hizo aparecer en el bundle. Por eso
 * los tokens se nombran acá en prosa y sin su prefijo.
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
  { fg: 'fg-subtle', bg: 'surface-subtle', min: AA_TEXT, what: 'texto de un control deshabilitado' },
  { fg: 'surface-subtle', bg: 'surface', min: AA_NON_TEXT, what: 'foco de teclado de la fila clickeable' },
  { fg: 'danger-border', bg: 'surface', min: AA_NON_TEXT, what: 'marco de la alerta de peligro' },
  { fg: 'danger-border', bg: 'canvas', min: AA_NON_TEXT, what: 'borde del botón de anular, que va sobre el fondo de página' },
  { fg: 'accent-border', bg: 'accent-soft', min: AA_NON_TEXT, what: 'marco de la caja informativa, contra su propio fondo' },
  { fg: 'accent-border', bg: 'surface', min: AA_NON_TEXT, what: 'marco de la caja informativa, que va dentro de una tarjeta' },
  { fg: 'success-border', bg: 'success-soft', min: AA_NON_TEXT, what: 'marco del aviso de éxito, contra su propio fondo' },
  { fg: 'warning-border', bg: 'warning-soft', min: AA_NON_TEXT, what: 'borde del botón de forzar, dentro del banner ámbar' },
  { fg: 'danger-border-strong', bg: 'danger-soft', min: AA_NON_TEXT, what: 'borde del botón de descartar, dentro de la alerta roja' },
  { fg: 'border-strong', bg: 'canvas', min: AA_NON_TEXT, what: 'borde del botón secundario, que va sobre el fondo de página' },
  { fg: 'on-solid', bg: 'accent', min: AA_TEXT, what: 'texto del botón primario' },
  { fg: 'on-solid', bg: 'accent-hover', min: AA_TEXT, what: 'texto del botón primario al pasar el mouse' },
  { fg: 'on-solid', bg: 'danger', min: AA_TEXT, what: 'texto del botón destructivo' },
  { fg: 'on-solid', bg: 'danger-hover', min: AA_TEXT, what: 'texto del botón destructivo con el mouse encima' },
  { fg: 'on-solid', bg: 'success', min: AA_TEXT, what: 'PREVENTIVO: el único relleno sólido verde es Stepper.tsx:64 y es emerald-500, no este token' },
  { fg: 'accent', bg: 'surface', min: AA_TEXT, what: 'enlace y texto de acento sobre tarjeta' },
  { fg: 'accent', bg: 'canvas', min: AA_NON_TEXT, what: 'anillo del spinner de carga sobre el fondo de página' },
  { fg: 'accent', bg: 'accent-soft', min: AA_TEXT, what: 'texto de la alerta informativa' },
  { fg: 'accent-hover', bg: 'surface', min: AA_TEXT, what: 'enlace en su tono fuerte, sobre tarjeta' },
  { fg: 'accent-hover', bg: 'canvas', min: AA_TEXT, what: 'etiqueta del paso visitado del asistente' },
  { fg: 'accent-hover', bg: 'surface-muted', min: AA_TEXT, what: 'opción del desplegable resaltada por teclado' },
  { fg: 'accent-hover', bg: 'accent-soft', min: AA_TEXT, what: 'total del asistente, ítem activo de la barra lateral y pastillas internas' },
  { fg: 'accent-hover', bg: 'accent-soft-strong', min: AA_TEXT, what: 'texto de la pastilla informativa y del círculo con el número de ítem' },
  { fg: 'accent-soft-strong', bg: 'accent-soft', min: AA_NON_TEXT, what: 'la pastilla informativa contra la fila integral que la contiene, que es el motivo por el que este token existe' },
  // Dos usos, no uno: el texto del error y el anillo de foco del botón destructivo. El
  // umbral queda en el del texto, que es el más estricto de los dos (4.5 contra 3).
  { fg: 'danger', bg: 'surface', min: AA_TEXT, what: 'mensaje de error bajo un campo, y anillo de foco del destructivo sobre tarjeta' },
  { fg: 'danger', bg: 'canvas', min: AA_TEXT, what: 'mensaje de error sobre el fondo de página, y anillo de foco del destructivo sobre el lienzo' },
  { fg: 'danger-fg', bg: 'surface', min: AA_TEXT, what: 'texto de peligro en su tono fuerte, sobre tarjeta' },
  { fg: 'danger-fg', bg: 'danger-soft', min: AA_TEXT, what: 'texto de la alerta de peligro' },
  { fg: 'danger-border-strong', bg: 'surface', min: AA_NON_TEXT, what: 'borde de un control en error' },
  { fg: 'warning', bg: 'surface', min: AA_TEXT, what: 'aviso de campo sobre tarjeta' },
  { fg: 'warning', bg: 'warning-soft', min: AA_TEXT, what: 'pastilla de aviso de Badge, y el valor del tile de stock bajo con el filtro activo' },
  { fg: 'warning', bg: 'warning-soft-strong', min: AA_TEXT, what: 'texto del chip que quita el filtro de stock bajo' },
  { fg: 'warning-fg', bg: 'warning-soft', min: AA_TEXT, what: 'texto de la alerta de aviso' },
  { fg: 'success', bg: 'surface', min: AA_TEXT, what: 'PREVENTIVO: no hay texto ni relleno con el tono sólido todavía' },
  { fg: 'success-fg', bg: 'surface', min: AA_TEXT, what: 'texto de entrada en el kardex' },
  { fg: 'success-fg', bg: 'success-soft', min: AA_TEXT, what: 'texto de la pastilla de éxito' },
  { fg: 'progress-fg', bg: 'progress-soft', min: AA_TEXT, what: 'texto de la pastilla del viaje en ruta' },
  { fg: 'transition-fg', bg: 'transition-soft', min: AA_TEXT, what: 'texto de la pastilla de la cotización aceptada y del cambio de estado en la bitácora' },
  { fg: 'focus', bg: 'surface', min: AA_NON_TEXT, what: 'anillo de foco sobre tarjeta' },
  { fg: 'focus', bg: 'canvas', min: AA_NON_TEXT, what: 'anillo de foco sobre el fondo' },
  { fg: 'border-strong', bg: 'surface', min: AA_NON_TEXT, what: 'borde del input sobre tarjeta' },
]

/**
 * Los dos tokens que NO entran en ningún par, con su razón. La guarda de
 * cobertura exige que esta lista y la de los tokens sin par coincidan exacto,
 * así que agregar un token obliga a decidir: o se mide, o se justifica acá.
 *
 * El único que queda es un borde puramente decorativo. WCAG 1.4.11 pide 3:1 al elemento
 * que COMUNICA algo (el borde de un control, el anillo de foco); un filete que
 * separa una tarjeta del fondo, o que enmarca una alerta cuyo mensaje ya se lee
 * por el texto y el color de fondo, no comunica nada por sí solo. Afirmarlos
 * contra un número inventado sería una prueba que no prueba nada.
 *
 * `danger-border` y `warning-border` estuvieron acá y salieron, por la misma
 * razón: en los dos casos hay un botón con relleno propio cuyo borde es el único
 * límite del control. Los dos se miden y los dos fallan, como `border-strong`.
 * Queda `border` solo, que es filete de tarjeta contra el fondo y nada más.
 */
const SIN_PAR: Record<string, string> = {
  border: 'filete de tarjeta y tabla contra el fondo: separación decorativa',
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
const KNOWN_FAILURES: Record<string, { ratio: number; note: string }> = {
  'fg-subtle/surface': {
    ratio: 2.63,
    note:
      'slate-400 sobre blanco. De sus 46 usos, 9 son placeholder (en 9 archivos), 2 son texto ' +
      'deshabilitado y unos 18 son íconos; el RESTO es texto corriente y ahí el 2.63 es un ' +
      'incumplimiento liso: entre otros, los dos encabezados de la barra lateral que además son ' +
      'el nombre accesible de sus listas de navegación (SidebarSection.tsx:12, ' +
      'SidebarFooter.tsx:17), y el contenido de celda de OpeningBalancesTable.tsx:79 y ' +
      'WithdrawalsTable.tsx:86. No es el peor par del sistema: el borde del control está más ' +
      'lejos de su umbral que este.',
  },
  'fg-subtle/canvas': {
    ratio: 2.51,
    note:
      'El mismo gris sobre el fondo de página. El caso más claro es la razón social del pie del ' +
      'login (LoginPage.tsx:140), que es texto corriente sobre bg-slate-50.',
  },
  'surface-subtle/surface': {
    ratio: 1.05,
    note:
      'El realce de la fila clickeable de DataTable.tsx:170-186 es también su ÚNICO indicador de ' +
      'foco de teclado, porque la fila lleva focus:outline-none. A 1.05 no se ve. Quien elija el ' +
      'valor oscuro de este token tiene que saber que está eligiendo un indicador de foco, no un ' +
      'realce cosmético.',
  },
  'danger-border/surface': {
    ratio: 1.45,
    note:
      'El marco de las nueve alertas de peligro. Los otros dos usos de este token son el borde ' +
      'del botón de anular, que no va sobre tarjeta sino sobre el fondo de página y se mide ' +
      'aparte.',
  },
  'fg-subtle/surface-subtle': {
    ratio: 2.51,
    note:
      'Texto de un control deshabilitado sobre su propio fondo (TextField.tsx:95). WCAG exime los ' +
      'controles inactivos, así que no es un incumplimiento; se mide igual para que el par no ' +
      'quede sin declarar y alguien lo descubra creyendo que es un hallazgo.',
  },
  'accent-border/accent-soft': {
    ratio: 1.67,
    note:
      'El marco de las tres cajas informativas contra su propio fondo. Es el mismo caso que ' +
      '`danger-border` y `warning-border`: un filete decorativo que acompaña al color de fondo, ' +
      'no la única señal del estado, que la da el fondo entero. No lo empeora el mapa: hoy ya ' +
      'es blue-300 y el token conserva ese valor.',
  },
  'accent-border/surface': {
    ratio: 1.81,
    note:
      'La misma caja, contra la tarjeta que la contiene. Mismo criterio que la anterior.',
  },
  'success-border/success-soft': {
    ratio: 1.13,
    note:
      'El marco del aviso de éxito, que HOY NO EXISTE: la variante entra sin ningún uso, así que ' +
      'este par no describe nada en pantalla todavía. Se declara igual para que el token no quede ' +
      'sin medir, y para que quien estrene el primer aviso de éxito vea el número antes de ' +
      'ponerlo: 1.13 es el más bajo de la familia y conviene mirarlo en pantalla.',
  },
  'warning-border/warning-soft': {
    ratio: 1.2,
    note:
      'El borde del botón de forzar (ResourceConflictAlert.tsx:138), que tiene relleno blanco ' +
      'dentro del banner ámbar: el borde es su único límite. Y el mapa lo empeora, porque hoy es ' +
      'amber-300 (1.40 contra el banner) y colapsa a amber-200.',
  },
  'danger-border-strong/danger-soft': {
    ratio: 1.75,
    note:
      'El mismo borde de control en error, contra el fondo suave: el botón de descartar de ' +
      'ServiceStatusErrorAlert.tsx:39 vive dentro de la alerta roja, no sobre tarjeta.',
  },
  'accent-soft-strong/accent-soft': {
    ratio: 1.12,
    note:
      'La pastilla informativa dentro de la fila de un servicio integral ' +
      '(QuotationItemsSection.tsx). Es el número que este token existe para conservar: el mapa ' +
      'mandaba los dos azules suaves al mismo valor y la pastilla pasaba a 1.00, o sea a ' +
      'desaparecer dentro de la fila. A 1.12 tampoco se separa por contraste, y no hace falta: ' +
      'lo que comunica es el texto "Integral", no el relleno.',
  },
  'fg-muted/accent-soft': {
    ratio: 4.38,
    note:
      'Las celdas secundarias de la fila de un servicio integral, que se pinta azul suave ' +
      '(QuotationItemsSection.tsx:23 y sus text-slate-500). Queda a 0.12 del mínimo.',
  },
  'danger-border-strong/surface': {
    ratio: 1.92,
    note:
      'red-300 sobre blanco: el borde de un control en estado de error (TextField.tsx:97 y otros ' +
      '22). Mismo régimen que border-strong, y el mismo incumplimiento anterior a este archivo. ' +
      'Existe como token propio, y no colapsado al marco de la alerta, justamente para no bajarlo ' +
      'de 1.92 a 1.45 en el barrido.',
  },
  'border-strong/canvas': {
    ratio: 1.42,
    note:
      'El mismo borde, contra el fondo de página, que es donde de verdad se dibuja el botón ' +
      'secundario: PageHeader.tsx:19 no tiene fondo propio. La cara de tarjeta vale para los ' +
      'inputs; esta, para los botones.',
  },
  'danger-border/canvas': {
    ratio: 1.39,
    note:
      'El borde del botón de anular contra el fondo de página. Su relleno blanco es del propio ' +
      'botón, no de una tarjeta: el lado que lo hace visible contra la página es este.',
  },
  'border-strong/surface': {
    ratio: 1.49,
    note:
      'slate-300 sobre blanco, contra el mínimo de 3:1 de 1.4.11 (no el de texto). Es el borde ' +
      'de todos los inputs, que sí viven en tarjeta blanca. El botón secundario usa el mismo ' +
      'token pero se dibuja sobre el fondo de página, y se mide aparte.',
  },
  'fg-muted/surface-muted': {
    ratio: 4.35,
    note:
      'slate-500 sobre slate-100. El lugar real es Combobox.tsx:273, el sublabel de la opción ' +
      'resaltada POR TECLADO, que es peor de lo que parece. Ojo: el hover de fila de DataTable y ' +
      'el fondo deshabilitado de TextField son slate-50, no slate-100, y ahí el par da 4.55 y pasa.',
  },
}

const cssPath = join(process.cwd(), 'src', 'index.css')
const css = readFileSync(cssPath, 'utf8')
const colors = parseThemeColors(css)
const key = (pair: Pair) => `${pair.fg}/${pair.bg}`

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
    const enElCss = new Map(
      [...compilado.matchAll(/--color-([A-Za-z0-9_-]+)\s*:\s*([^;}]+)[;}]/g)].map((m) => [
        m[1],
        m[2].trim(),
      ]),
    )
    // El CSS trae además la paleta de Tailwind, que el escáner arrastra al
    // compilar con las clases reales. Lo que se afirma es que CADA token del
    // tema esté publicado y con su valor, no que sean los únicos.
    const publicados = Object.keys(colors)
      .map((n) => `${n}: ${enElCss.get(n)}`)
      .sort()
    expect(publicados).toEqual(
      Object.entries(colors)
        .map(([n, v]) => `${n}: ${v}`)
        .sort(),
    )

    // Y que la variable se REFERENCIE en la utilidad en vez de hornearse: el
    // modificador `inline` publica los 27 tokens igual y mete el literal en cada
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
   * Forzador para el PR del modo oscuro. Cuando entren los valores oscuros, en
   * el archivo que sea y con la forma que sea, esta guarda falla y obliga a
   * extender la medición en vez de dejar la suite en verde midiendo la mitad,
   * que es justo donde el contraste se rompe.
   */
  it('todavía no se publica un segundo tema, y cuando se publique hay que medirlo acá', () => {
    // Cualquier atributo de tema, cualquier clase que lo nombre, la consulta de
    // preferencia del sistema, y un segundo bloque de tokens en otro selector.
    // Medido: con solo `[data-theme` se colaban `[data-mode="dark"]`,
    // `.theme-dark`, `html[data-appearance=dark]` y un segundo `@theme static`.
    expect(compilado).not.toMatch(/\[data-(theme|mode|appearance)|dark|prefers-color-scheme/i)
    // Un segundo lugar que declare tokens es un segundo tema, se llame como se
    // llame: las variables del tema viven en un solo bloque.
    const bloquesConTokens = [...compilado.matchAll(/\{[^{}]*--color-[a-z0-9-]+\s*:/g)]
    expect(bloquesConTokens).toHaveLength(1)
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
   * La directiva que saca la carpeta del tema del escaneo, afirmada sobre la
   * lectura que hace TAILWIND (`compile()` devuelve sus fuentes ya parseadas) y
   * no sobre un regex nuestro.
   *
   * ALCANCE, con precisión: esto afirma qué patrones de exclusión hay y cuáles
   * son, no qué archivos terminan escaneados. Lo segundo SÍ se puede medir sin
   * un build completo, con el `Scanner` de `@tailwindcss/oxide` (hoy transitiva
   * de `@tailwindcss/vite`), en unos 60 ms; no se hizo acá para no sumar una
   * dependencia declarada a este PR, y queda anotado como la mejora natural de
   * esta guarda. Una versión anterior de este comentario decía que el efecto no
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
      'fg-muted': '#62748e', // slate-500
      'fg-subtle': '#90a1b9', // slate-400
      'border': '#e2e8f0', // slate-200
      'border-strong': '#cad5e2', // slate-300
      'accent': '#155dfc', // blue-600
      'accent-hover': '#1447e6', // blue-700
      'accent-soft': '#eff6ff', // blue-50
      'accent-soft-strong': '#dbeafe', // blue-100
      'on-solid': '#ffffff', // white
      'focus': '#2b7fff', // blue-500
      'danger': '#e7000b', // red-600
      'danger-hover': '#c10007', // red-700
      'danger-soft': '#fef2f2', // red-50
      'danger-fg': '#c10007', // red-700
      'accent-border': '#8ec5ff', // blue-300
      'danger-border': '#ffc9c9', // red-200
      'danger-border-strong': '#ffa2a2', // red-300
      'warning': '#bb4d00', // amber-700
      'warning-soft': '#fffbeb', // amber-50
      'warning-soft-strong': '#fef3c6', // amber-100
      'warning-fg': '#973c00', // amber-800
      'success-border': '#a4f4cf', // emerald-200
      'warning-border': '#fee685', // amber-200
      'progress-soft': '#ede9fe', // violet-100
      'progress-fg': '#7008e7', // violet-700
      'transition-soft': '#cbfbf1', // teal-100
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
   * declara en su comentario. Los 27 dan byte a byte: no hay tolerancia ni
   * aproximación. Si un upgrade de Tailwind mueve un color de la paleta, esto lo
   * dice, que es la otra mitad de lo que este caso protege.
   */
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
      'accent-border/accent-soft',
      'accent-border/surface',
      'accent-hover/accent-soft',
      'accent-hover/accent-soft-strong',
      'accent-hover/canvas',
      'accent-hover/surface',
      'accent-hover/surface-muted',
      'accent-hover/surface-subtle',
      'accent-soft-strong/accent-soft',
      'accent/accent-soft',
      'accent/canvas',
      'accent/surface',
      'border-strong/canvas',
      'border-strong/surface',
      'danger-border-strong/danger-soft',
      'danger-border-strong/surface',
      'danger-border/canvas',
      'danger-border/surface',
      'danger-fg/danger-soft',
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
      'focus/canvas',
      'focus/surface',
      'on-solid/accent',
      'on-solid/accent-hover',
      'on-solid/danger',
      'on-solid/danger-hover',
      'on-solid/success',
      'progress-fg/progress-soft',
      'success-border/success-soft',
      'success-fg/success-soft',
      'success-fg/surface',
      'success/surface',
      'surface-subtle/surface',
      'transition-fg/transition-soft',
      'warning-border/warning-soft',
      'warning-fg/surface',
      'warning-fg/warning-soft',
      'warning/surface',
      'warning/warning-soft',
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
  it('el conjunto de excepciones es exactamente este', () => {
    expect(Object.keys(KNOWN_FAILURES).sort()).toEqual([
      'accent-border/accent-soft',
      'accent-border/surface',
      'accent-soft-strong/accent-soft',
      'border-strong/canvas',
      'border-strong/surface',
      'danger-border-strong/danger-soft',
      'danger-border-strong/surface',
      'danger-border/canvas',
      'danger-border/surface',
      'fg-muted/accent-soft',
      'fg-muted/surface-muted',
      'fg-subtle/canvas',
      'fg-subtle/surface',
      'fg-subtle/surface-subtle',
      'success-border/success-soft',
      'surface-subtle/surface',
      'warning-border/warning-soft',
    ])
  })

  it('no quedan excepciones declaradas sobre pares que ya no existen', () => {
    const declarados = new Set(PAIRS.map(key))
    const huerfanas = Object.keys(KNOWN_FAILURES).filter((k) => !declarados.has(k))
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
      'accent-border/accent-soft',
      'accent-border/surface',
      'accent-soft-strong/accent-soft',
      'accent/canvas',
      'border-strong/canvas',
      'border-strong/surface',
      'danger-border-strong/danger-soft',
      'danger-border-strong/surface',
      'danger-border/canvas',
      'danger-border/surface',
      'focus/canvas',
      'focus/surface',
      'success-border/success-soft',
      'surface-subtle/surface',
      'warning-border/warning-soft',
    ].sort(),
    )
  })
})

describe('contraste del tema claro', () => {
  const conocidos = PAIRS.filter((pair) => key(pair) in KNOWN_FAILURES)
  const exigidos = PAIRS.filter((pair) => !(key(pair) in KNOWN_FAILURES))

  it.each(exigidos.map((pair) => [key(pair), pair] as const))(
    '%s llega al mínimo',
    (_name, pair) => {
      const ratio = contrastRatio(colors[pair.fg], colors[pair.bg])
      expect(
        ratio,
        `${pair.fg} sobre ${pair.bg} (${pair.what}) da ${ratio.toFixed(2)}:1 y necesita ${pair.min}:1`,
      ).toBeGreaterThanOrEqual(pair.min)
    },
  )

  it.each(conocidos.map((pair) => [key(pair), pair] as const))(
    '%s sigue siendo una excepción conocida',
    (name, pair) => {
      const esperado = KNOWN_FAILURES[name]
      const ratio = contrastRatio(colors[pair.fg], colors[pair.bg])
      expect(
        Number(ratio.toFixed(2)),
        `${name} cambió de ${esperado.ratio}:1 a ${ratio.toFixed(2)}:1. Si lo arreglaste, ` +
          `borrá su entrada de KNOWN_FAILURES: la excepción ya no corresponde.`,
      ).toBe(esperado.ratio)
      expect(
        ratio,
        `${name} ya llega a ${pair.min}:1. Borrá su entrada de KNOWN_FAILURES.`,
      ).toBeLessThan(pair.min)
    },
  )
})
