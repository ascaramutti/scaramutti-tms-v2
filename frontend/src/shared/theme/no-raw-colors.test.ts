import { readdirSync, readFileSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { beforeAll, describe, expect, it } from 'vitest'
import { compileGlobalCss } from './compileCss'

/**
 * La guarda que impide que el barrido se deshaga. Es la capa 2 de la sección 5.4 del
 * documento de diseño: la que no se puede desactivar desde el editor y la que da el número
 * exacto. La capa 1 es la regla de ESLint, que avisa al escribir y que no ve las cadenas
 * construidas.
 *
 * El diseño la pensó arrancando en 1246 y bajando con cada PR del barrido. Llega tarde y
 * llega mejor: los siete PRs del tema ya la dejaron en CERO, así que en vez de un tope que
 * baja, afirma que no hay ninguno. La forma de tope se conserva igual, porque el día que
 * alguien tenga que dejar uno con motivo escrito, esto le dice exactamente cuántos hay.
 *
 * Vive en esta carpeta a propósito: está excluida del escaneo de Tailwind, así que los
 * nombres de familia que menciona el patrón no publican una sola regla. En cualquier otro
 * lado, una prueba que habla de clases las crea.
 */

/** Cuántos colores escritos a mano se aceptan hoy. **Este número solo puede bajar.** */
const TOPE = 0

const PROPIEDADES =
  'bg|text|border|ring|divide|placeholder|outline|accent|caret|decoration|fill|stroke|shadow|from|to|via'
const FAMILIAS =
  'slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|' +
  'indigo|violet|purple|fuchsia|pink|rose'
/** El sufijo numérico es opcional para que `white` y `black` no se escapen, que es el agujero
 *  que el propio diseño le señala a la regla de ESLint. */
const CRUDO = new RegExp(
  `\\b(?:[a-z-]+:)*(?:${PROPIEDADES})-(?:(?:${FAMILIAS})-\\d{2,3}|white|black)(?:/\\d{1,3})?\\b`,
  'g',
)
/**
 * Los comentarios no cuentan: lo que se persigue acá es la clase que un elemento aplica.
 *
 * La barra doble NO se descarta cuando viene pegada a dos puntos, y eso no es un detalle: en
 * un `.html` o un `.md` no hay comentarios de línea, pero sí hay URLs, y borrar desde la barra
 * de un `https://` se lleva el resto del renglón. La revisión midió el agujero exacto: una
 * clase escrita detrás de una URL en el `index.html` se publicaba en el bundle con las dos
 * capas en verde.
 */
const COMENTARIO = /\/\*[\s\S]*?\*\/|(?<!:)\/\/[^\n]*/g

/**
 * El corpus sale del MISMO escáner que usa el compilador, con las exclusiones que declara el
 * CSS ya aplicadas, en vez de una lista escrita a mano. La lista a mano decía `src` más el
 * `index.html`, y su comentario afirmaba que ahí terminaba lo que Tailwind mira: la revisión
 * midió que una clase puesta en la configuración de ESLint o en el README también llega a la
 * hoja publicada, y ninguna de las dos capas la veía. Una lista escrita a mano envejece
 * calladita; el escáner no.
 */
let escaneados: string[] = []

beforeAll(async () => {
  escaneados = (await compileGlobalCss(process.cwd())).archivos
})

const esPrueba = (ruta: string) => ruta.includes('.test.')
const relativo = (ruta: string) => relative(process.cwd(), ruta)

function crudosEn(archivos: string[]): string[] {
  return archivos.flatMap((ruta) => {
    const texto = readFileSync(ruta, 'utf8').replace(COMENTARIO, '')
    return [...texto.matchAll(CRUDO)].map((m) => `${relativo(ruta)}: ${m[0]}`)
  })
}

describe('no quedan colores escritos a mano', () => {
  it(`no superan el tope declarado, que hoy es ${TOPE}`, () => {
    const hallazgos = crudosEn(escaneados.filter((ruta) => !esPrueba(ruta)))
    expect(
      hallazgos.length,
      hallazgos.length > TOPE
        ? `Aparecieron colores sin token:\n  ${hallazgos.join('\n  ')}\n` +
          'Usá un token del tema. Si de verdad no hay ninguno que sirva, se agrega uno por ' +
          'función y se decide con el dueño: no se sube este tope sin eso.'
        : '',
    ).toBeLessThanOrEqual(TOPE)
  })

  /**
   * La otra mitad, y la que hace que el tope sirva: si alguien lo sube "temporalmente", este
   * caso falla igual. Un tope que puede subir es un contador, no una guarda.
   */
  it('el tope está en cero y no puede volver a subir sin que se note', () => {
    expect(
      TOPE,
      'el barrido terminó en cero: subir este número es aceptar que el tema se deshace',
    ).toBe(0)
  })

  /**
   * Y las PRUEBAS, que son un punto ciego: Tailwind escanea todo lo que no esté excluido, así
   * que un color crudo en una aserción se publica en el bundle igual que uno de producto. Acá
   * no cuenta para el tope, porque una prueba no pinta nada, pero no puede haber ninguno.
   */
  it('ninguna prueba escaneada nombra un color crudo', () => {
    expect(crudosEn(escaneados.filter(esPrueba))).toEqual([])
  })

  /**
   * Y que el patrón siga viendo algo: una expresión regular rota no encuentra nada y deja
   * este archivo pasando en verde para siempre. Se prueba contra un texto de mentira, no
   * contra el árbol.
   */
  it('el patrón encuentra lo que tiene que encontrar', () => {
    const muestra = [
      'className="bg-slate-100 text-fg"',
      'className="hover:text-red-600"',
      'className="bg-white"',
      'className="bg-slate-900/50"',
      'className="shadow-slate-200"',
      'className="bg-surface text-fg-muted border-border"',
    ].join('\n')
    // El conjunto EXACTO y no la cantidad: cinco coincidencias distintas de las esperadas
    // darían el mismo verde.
    const encontrados = [...muestra.matchAll(CRUDO)].map((m) => m[0])
    expect(encontrados).toEqual([
      'bg-slate-100',
      'hover:text-red-600',
      'bg-white',
      'bg-slate-900/50',
      'shadow-slate-200',
    ])
  })

  /** Y que el borrado de comentarios no se lleve puesto lo que sigue a una URL. */
  it('una clase detrás de una URL sigue contando', () => {
    const muestra = '<link href="https://fonts.example/css" /><div class="bg-red-500">'
    const encontrados = [...muestra.replace(COMENTARIO, '').matchAll(CRUDO)].map((m) => m[0])
    expect(encontrados).toEqual(['bg-red-500'])
  })

  /**
   * La red de la red: si el corpus se vacía, todo lo de arriba pasa al vacío para siempre. No
   * alcanza con un piso holgado (el anterior toleraba perder un tercio del árbol sin decir
   * nada): se compara contra el árbol de verdad, archivo por archivo.
   */
  it('el corpus tiene todos los componentes y ninguno de la carpeta del tema', () => {
    const src = join(process.cwd(), 'src')
    const delArbol = readdirSync(src, { recursive: true })
      .map(String)
      .filter((f) => f.endsWith('.tsx') || f.endsWith('.ts'))
      .filter((f) => !f.startsWith(`shared${sep}theme${sep}`))
      .map((f) => join(src, f))
    expect(escaneados, 'el escáner dejó de ver parte del árbol').toEqual(
      expect.arrayContaining(delArbol),
    )
    expect(escaneados, 'el `index.html` publica clases igual que un componente').toContain(
      join(process.cwd(), 'index.html'),
    )
    // Y el efecto de la directiva de exclusión, que hasta acá solo se afirmaba como texto: si
    // la carpeta del tema entrara al escaneo, los nombres de familia de este archivo se
    // volverían reglas de verdad en el bundle.
    expect(escaneados.filter((ruta) => ruta.includes(join('shared', 'theme')))).toEqual([])
  })
})

/** Cada cadena de clases de un texto: entre comillas simples, dobles o acentos graves. */
const CADENA = /'([^']*)'|"([^"]*)"|`([^`]*)`/g
/** Un anillo separado del control, con el prefijo de estado que lo dispara. */
const SEPARA = /(?:^|\s)((?:[a-z-]+:)?)ring-offset-(?:0|1|2|4|8)(?![\w-])/g

/**
 * Las cadenas de clases que separan el anillo sin decir de qué color es la banda.
 *
 * Se corta por la CADENA y no por una ventana de caracteres alrededor. La primera versión
 * miraba 400 caracteres a cada lado, y eso mide la distancia del texto y no la declaración:
 * dos controles vecinos del mismo archivo se prestaban el color, y sacarle el suyo al primero
 * dejaba la guarda en verde. La revisión lo midió sobre el botón compartido, cuyas dos
 * variantes viven a 270 caracteres una de la otra.
 */
export function cadenasSinBanda(texto: string): string[] {
  return [...texto.matchAll(CADENA)]
    .map((c) => c[1] ?? c[2] ?? c[3] ?? '')
    .filter((cadena) =>
      [...cadena.matchAll(SEPARA)].some(
        (m) =>
          !cadena.includes(`${m[1]}ring-offset-surface`) &&
          !cadena.includes(`${m[1]}ring-offset-canvas`),
      ),
    )
}

/**
 * La banda del anillo de foco, que es la que dejó el modo oscuro con un halo blanco.
 *
 * Tailwind declara `--tw-ring-offset-color` con un `#fff` cableado en un `@property` con
 * `inherits: false`: no hay forma de redefinirlo desde el tema, porque el valor de `:root` no
 * llega al elemento. La única salida es que cada sitio que separa su anillo diga además de
 * qué color es esa banda. En claro el token de la tarjeta vale exactamente el blanco de hoy, y
 * el de la página es el gris apenas más frío que ya tenía detrás, así que ninguno de los dos
 * cambia lo que se ve; en oscuro los dos siguen al tema.
 *
 * Cuál de los dos tokens va lo decide el fondo que el control tiene DETRÁS, porque la banda se
 * dibuja fuera de su caja y por lo tanto la pinta el padre: el de la página para los botones de
 * encabezado, el del panel para los que viven dentro de una tarjeta, un formulario o un modal.
 * El botón compartido se queda con el de la tarjeta, que es donde está la mayoría de sus usos;
 * el sitio que lo saque a la página le pasa el otro y `twMerge` lo deja ganar.
 *
 * Esto existe porque el arreglo se aplicó a mano y llegó a 8 de 21 sitios: los 13 que faltaban
 * eran los que terminan contra una comilla, que el patrón del barrido no veía. Sin esta
 * guarda, el sitio 22 lo estrena de nuevo.
 */
describe('el anillo de foco separado siempre dice de qué color es su banda', () => {
  const raiz = join(process.cwd(), 'src')
  const deProducto = readdirSync(raiz, { recursive: true })
    .map(String)
    .filter((f) => (f.endsWith('.tsx') || f.endsWith('.ts')) && !f.includes('.test.'))
    .map((f) => join(raiz, f))

  it('no queda ningún sitio con la banda sin color', () => {
    const sinColor = deProducto.flatMap((ruta) =>
      cadenasSinBanda(readFileSync(ruta, 'utf8').replace(COMENTARIO, '')).map(() => relativo(ruta)),
    )
    expect(
      sinColor,
      'sin color de banda, en modo oscuro el anillo queda envuelto en un halo blanco de 2 px',
    ).toEqual([])
  })

  /**
   * Y que la guarda vea las que hay: si el patrón dejara de encontrar declaraciones, el caso de
   * arriba pasaría al vacío para siempre.
   */
  it('encuentra las declaraciones que tiene que mirar', () => {
    const vistas = deProducto.reduce((n, ruta) => {
      const texto = readFileSync(ruta, 'utf8').replace(COMENTARIO, '')
      return n + [...texto.matchAll(SEPARA)].length
    }, 0)
    expect(vistas, 'el patrón dejó de ver las bandas del árbol').toBeGreaterThan(15)
  })

  /** Y el modo de falla exacto que la tenía ciega: el color del control de al lado. */
  it('el color de un control vecino no cubre al de al lado', () => {
    const muestra = [
      "const PRIMARIO = 'focus:ring-2 focus:ring-focus focus:ring-offset-2'",
      "const PELIGRO = 'focus:ring-2 focus:ring-danger focus:ring-offset-2 focus:ring-offset-surface'",
    ].join('\n')
    expect(cadenasSinBanda(muestra)).toHaveLength(1)
  })

  /** Y que el prefijo de estado tenga que coincidir: un `focus:` no lo cubre un `focus-visible:`. */
  it('el prefijo de estado del color tiene que ser el mismo', () => {
    const mezclado = "className=\"focus:ring-offset-2 focus-visible:ring-offset-surface\""
    expect(cadenasSinBanda(mezclado)).toHaveLength(1)
  })
})

/**
 * La capa 1 de la sección 5.4, medida. Es la regla de ESLint, que avisa en el editor antes de
 * que el color llegue a un commit; hasta acá nada confirmaba que dispare, así que apagarla o
 * romperle el patrón en un refactor no ponía nada en rojo. Se ejecuta la configuración real del
 * proyecto sobre un texto de mentira, no sobre el árbol.
 */
describe('la regla del editor', () => {
  it('caza un color crudo escrito en un componente', async () => {
    const { ESLint } = await import('eslint')
    const linter = new ESLint({ cwd: process.cwd() })
    const [resultado] = await linter.lintText('export const P = "bg-red-500"\n', {
      filePath: join(process.cwd(), 'src', 'shared', 'ui', 'ZzPrueba.tsx'),
    })
    expect(resultado.errorCount, 'la regla dejó de disparar').toBeGreaterThan(0)
    expect(resultado.messages.map((m) => m.ruleId)).toContain('no-restricted-syntax')
  })

  it('no molesta a la carpeta que mide el tema, que habla de clases por oficio', async () => {
    const { ESLint } = await import('eslint')
    const linter = new ESLint({ cwd: process.cwd() })
    const [resultado] = await linter.lintText('export const P = "bg-red-500"\n', {
      filePath: join(process.cwd(), 'src', 'shared', 'theme', 'ZzPrueba.ts'),
    })
    expect(resultado.messages.map((m) => m.ruleId)).not.toContain('no-restricted-syntax')
  })
})
