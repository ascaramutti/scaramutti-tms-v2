import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Todo control que reciba el foco tiene que decir cómo se ve al recibirlo.
 *
 * Existe por un defecto que estuvo embarcado y que ninguna prueba veía: la fila clickeable de la
 * tabla llevaba el contorno apagado y, como única señal de foco, un tinte de fondo que mide 1.05
 * contra la fila en reposo. O sea que quien navega con el tabulador no sabía dónde estaba. No es
 * un problema de color que se arregle eligiendo mejor el tinte: era invisible por diseño.
 *
 * La guarda distingue los dos estados, porque no son el mismo problema:
 *
 *   APAGADO       el elemento apaga el contorno del navegador y no pone nada propio. El foco no
 *                 se ve. Es incumplimiento de 2.4.7 y es lo que esta guarda existe para atajar.
 *   SIN DECLARAR  el elemento no dice nada. El navegador dibuja su anillo, que se ve pero no
 *                 sigue al tema: en oscuro es el único color que el tema no controla.
 *
 * Las dos fallan acá. La segunda no es una barrera, pero dejarla pasar es como se llega a la
 * primera: alguien agrega `outline-none` para que "no se vea el borde feo" y no pone el reemplazo.
 *
 * Vive en esta carpeta a propósito: está excluida del escaneo de Tailwind, así que los nombres de
 * clase que menciona no publican una sola regla.
 */

/**
 * Lo que cuenta como declarar el foco: un anillo o un contorno **con un color del tema pensado para
 * señalar**. Que exista una declaración no alcanza, y no es teórico: la revisión cambió el anillo
 * del modal por el token del borde, que mide 1.23 contra la tarjeta, y una versión anterior de esta
 * guarda lo daba por bueno. Un anillo invisible pasaba entera.
 *
 * Los dos colores permitidos son los dos que el árbol usa: el de foco, y el de peligro para los
 * controles destructivos, que lo llevan a propósito para que la señal diga también qué va a pasar.
 * Los dos están medidos como par en la prueba de contraste. Cualquier otro token hay que agregarlo
 * acá a mano, que es el punto: obliga a medirlo antes.
 */
const COLOR_DE_FOCO = 'focus|danger'
const DECLARA = new RegExp(`focus(?:-visible)?:(?:ring|outline|border|shadow)-(?:${COLOR_DE_FOCO})(?![\\w-])`)
/** Apagar el contorno sin poner nada: el estado grave. */
const APAGA = /focus(?:-visible)?:outline-none|(?<![\w-])outline-none(?![\w-])/

/**
 * Las etiquetas que reciben el foco por sí solas. `Link` y `NavLink` rinden un `<a>` sin una sola
 * clase propia, así que cuentan igual que la etiqueta cruda; los componentes de la casa traen su
 * foco adentro y se miran en el archivo donde viven.
 */
const FOCALIZABLE = /<(button|a|input|select|textarea|Link|NavLink)(?=[\s/>])/g
/** Y cualquier otro elemento al que le pongan `tabIndex` entra en el orden de tabulación. */
const CON_TABINDEX = /<([A-Za-z][\w.]*)(?=[\s>])/g

/**
 * ~~El alcance: lo compartido más los archivos que este PR tocó.~~ **El alcance es TODO `src`.**
 *
 * La primera versión enumeraba catorce archivos a mano, y la revisión midió lo que eso dejaba
 * afuera: metiendo el defecto exacto que este trabajo arregla en una pantalla que no estaba en la
 * lista, la guarda seguía en verde. Una guarda con lista blanca protege lo que ya está arreglado y
 * no lo que va a escribirse mañana, que es lo que hace falta.
 *
 * Se puede recorrer el árbol entero porque hoy da cero: no hay ningún control sin declarar en
 * ninguna pantalla. Esta lista queda solo como recordatorio de los archivos que el PR tocó.
 */
const TOCADOS = [
  'features/operations/components/ServicesKpiStrip.tsx',
  'features/operations/components/resources/ResourceConflictAlert.tsx',
  'features/quotations/wizard/ChildItemCard.tsx',
  'features/quotations/wizard/ItemCard.tsx',
  'features/quotations/wizard/StepConditions.tsx',
  'features/quotations/wizard/StepStandBy.tsx',
  'features/warehouse/components/EntryCancelModal.tsx',
  'features/warehouse/components/EntryForm.tsx',
  'features/warehouse/components/OpeningBalanceForm.tsx',
  'features/warehouse/components/ProductFormModal.tsx',
  'features/warehouse/components/WarehouseKpiStrip.tsx',
  'features/warehouse/components/WithdrawalCancelModal.tsx',
  'features/warehouse/components/WithdrawalForm.tsx',
  'features/warehouse/pages/WarehouseReportsPage.tsx',
]

const SRC = join(process.cwd(), 'src')

function sinComentarios(texto: string): string {
  return texto.replace(/\/\*[\s\S]*?\*\/|(?<!:)\/\/[^\n]*/g, (m) => '\n'.repeat((m.match(/\n/g) ?? []).length))
}

/** El texto de la expresión que arranca en `i`, hasta que cierran sus paréntesis y llaves. */
function expresionDesde(texto: string, i: number): string {
  let prof = 0
  let arranco = false
  for (let j = i; j < texto.length; j++) {
    const c = texto[j]
    if (c === '(' || c === '[' || c === '{') {
      prof++
      arranco = true
    } else if (c === ')' || c === ']' || c === '}') {
      prof--
      if (prof <= 0 && arranco) return texto.slice(i, j + 1)
    } else if (c === '\n' && prof === 0 && texto.slice(i, j).trim()) {
      return texto.slice(i, j)
    }
  }
  return texto.slice(i)
}

/** Las constantes de módulo, guardadas como EXPRESIÓN: sus clases pueden venir de una llamada. */
function constantes(texto: string): Map<string, string> {
  const fuera = new Map<string, string>()
  for (const m of texto.matchAll(/(?:export )?const (\w+)(?::\s*[^=\n]*)? =/g)) {
    fuera.set(m[1], expresionDesde(texto, m.index + m[0].length))
  }
  return fuera
}

/** Nombre -> archivo, para los imports relativos, que son por donde llega el molde de campos. */
function importados(texto: string, ruta: string): Map<string, string> {
  const fuera = new Map<string, string>()
  for (const m of texto.matchAll(/import\s+(?:\{([^}]*)\}|(\w+))\s+from\s+'(\.[^']*)'/g)) {
    const nombres = m[1] ? m[1].split(',').map((n) => n.split(' as ').pop()!.trim()) : [m[2]]
    for (const ext of ['.ts', '.tsx', '/index.ts', '/index.tsx']) {
      const cand = resolve(dirname(ruta), m[3]) + ext
      try {
        readFileSync(cand)
        for (const n of nombres) if (n) fuera.set(n, cand)
        break
      } catch {
        // no es ese archivo: se prueba la extensión siguiente
      }
    }
  }
  return fuera
}

const cacheModulo = new Map<string, string>()
function literalesDe(ruta: string): string {
  if (!cacheModulo.has(ruta)) {
    const t = readFileSync(ruta, 'utf8')
    cacheModulo.set(ruta, [...t.matchAll(/'([^']*)'|`([^`]*)`/g)].map((m) => m[1] ?? m[2]).join(' '))
  }
  return cacheModulo.get(ruta)!
}

function expandir(
  expr: string,
  consts: Map<string, string>,
  imports: Map<string, string>,
  visto = new Set<string>(),
  prof = 0,
): string {
  if (prof > 4) return ''
  const partes = [...expr.matchAll(/'([^']*)'|"([^"]*)"/g)].map((m) => m[1] ?? m[2])
  for (const m of expr.matchAll(/`([^`]*)`/g)) partes.push(m[1].replace(/\$\{[^}]*\}/g, ' '))
  for (const m of expr.matchAll(/\b([A-Za-z_]\w*)\b/g)) {
    const ident = m[1]
    if (visto.has(ident)) continue
    if (consts.has(ident)) {
      partes.push(expandir(consts.get(ident)!, consts, imports, new Set([...visto, ident]), prof + 1))
    } else if (imports.has(ident)) {
      partes.push(literalesDe(imports.get(ident)!))
    }
  }
  return partes.join(' ')
}

/**
 * Los atributos que un tag declara SIN valor, que en JSX significan `true`. Se leen contando
 * profundidad de llaves y paréntesis, porque lo que va adentro de una expresión no es un atributo.
 */
function atributosSinValor(tag: string): Set<string> {
  const fuera = new Set<string>()
  let prof = 0
  let comilla: string | null = null
  let nombre = ''
  for (let i = 1; i < tag.length; i++) {
    const c = tag[i]
    // Las cadenas se saltan enteras: adentro viven las clases, y una de ellas es la variante
    // `disabled:` de Tailwind, que no es un atributo. Medido: sin esto, nueve controles con esa
    // clase salían como deshabilitados.
    if (comilla) {
      if (c === comilla) comilla = null
      nombre = ''
      continue
    }
    if (c === '"' || c === "'" || c === '`') {
      comilla = c
      nombre = ''
      continue
    }
    if (c === '{' || c === '(') prof++
    else if (c === '}' || c === ')') prof--
    else if (prof === 0) {
      if (/[\w-]/.test(c)) {
        nombre += c
        continue
      }
      if (nombre && c !== '=') fuera.add(nombre)
      nombre = ''
      continue
    }
    nombre = ''
  }
  if (nombre) fuera.add(nombre)
  return fuera
}

/** El texto de la etiqueta de apertura que empieza en `i`, con las llaves balanceadas. */
function tagDesde(texto: string, i: number): string {
  let prof = 0
  for (let j = i; j < texto.length; j++) {
    const c = texto[j]
    if (c === '{') prof++
    else if (c === '}') prof--
    else if (c === '>' && prof === 0) return texto.slice(i, j + 1)
  }
  return texto.slice(i)
}

interface Hallazgo {
  sitio: string
  estado: 'APAGADO' | 'SIN DECLARAR'
}

/**
 * Los controles de un archivo que no declaran su foco. Se junta TODO lo que aporta clases a un
 * mismo elemento antes de decidir: literales, plantillas, constantes locales (que pueden ser una
 * llamada a otro módulo) y lo que llega por un import. Mirar una cadena por vez da un falso
 * positivo por cada `cn()` de dos partes, y midiéndolo se vio que eran más de la mitad.
 */
export function controlesSinFoco(texto: string, ruta: string): Hallazgo[] {
  const limpio = sinComentarios(texto)
  const consts = constantes(limpio)
  const imports = importados(limpio, ruta)
  const fuera: Hallazgo[] = []
  const vistos = new Set<number>()
  const candidatos = [...limpio.matchAll(FOCALIZABLE), ...limpio.matchAll(CON_TABINDEX)]
  for (const m of candidatos) {
    const i = m.index
    if (vistos.has(i)) continue
    const tag = tagDesde(limpio, i)
    const propio = /^<(button|a|input|select|textarea|Link|NavLink)(?=[\s/>])/.test(tag)
    if (!propio && !/tabIndex/.test(tag)) continue
    if (!propio && /tabIndex=\{?-1/.test(tag)) continue
    // Un control deshabilitado con el atributo puesto a secas nunca recibe el foco, así que
    // exigirle que lo declare es pedir clases que ningún navegador va a aplicar. Con
    // `disabled={algo}` no vale el atajo: ese `algo` puede ser falso. Lo levantó la revisión,
    // sobre una casilla cuyo comentario decía que a propósito NO lleva anillo.
    //
    // Y se mira SOLO el nivel de arriba del tag: la palabra `disabled` aparece también dentro de
    // sus expresiones (`disabled={disabled}`, `cn(disabled && '...')`), y esas llaves se anidan,
    // así que no alcanza con borrarlas por patrón. Medido: un patrón de un nivel daba por
    // deshabilitados a dos controles que sí reciben el foco.
    if (atributosSinValor(tag).has('disabled')) continue
    vistos.add(i)
    const cl = /className=(?:"([^"]*)"|\{)/.exec(tag)
    const clases =
      cl == null
        ? ''
        : cl[1] !== undefined
          ? cl[1]
          : expandir(expresionDesde(tag, cl.index + cl[0].length - 1), consts, imports)
    if (DECLARA.test(clases)) continue
    const linea = limpio.slice(0, i).split('\n').length
    fuera.push({
      sitio: `${relative(SRC, ruta)}:${linea} <${m[1]}>`,
      estado: APAGA.test(clases) ? 'APAGADO' : 'SIN DECLARAR',
    })
  }
  return fuera
}

function archivosDelAlcance(): string[] {
  return readdirSync(SRC, { recursive: true })
    .map(String)
    .filter((f) => f.endsWith('.tsx') && !f.includes('.test.'))
    .map((f) => join(SRC, f))
}

describe('todo control focalizable dice cómo se ve al recibir el foco', () => {
  const archivos = archivosDelAlcance()

  it('no queda ninguno con el foco apagado ni sin declarar', () => {
    const hallazgos = archivos.flatMap((ruta) =>
      controlesSinFoco(readFileSync(ruta, 'utf8'), ruta).map((h) => `${h.estado}  ${h.sitio}`),
    )
    expect(
      hallazgos,
      'un control sin foco declarado no se puede usar con el teclado, o se usa con el anillo del ' +
        'navegador, que es el único color que el tema no controla',
    ).toEqual([])
  })

  /** Y que la guarda vea el árbol: si el recorrido se vacía, lo de arriba pasa al vacío. */
  it('el alcance es el árbol entero, y contiene los archivos que este trabajo tocó', () => {
    expect(archivos.length, 'el recorrido dejó de ver pantallas').toBeGreaterThan(120)
    expect(archivos).toContain(join(SRC, 'shared', 'ui', 'DataTable.tsx'))
    for (const f of TOCADOS) expect(archivos).toContain(join(SRC, f))
  })

  /**
   * Y los dos modos de falla, contra un texto de mentira: una expresión regular rota no encuentra
   * nada y deja este archivo en verde para siempre.
   */
  it('distingue el foco apagado del foco sin declarar', () => {
    const ruta = join(SRC, 'shared', 'ui', 'Falso.tsx')
    const apagado = '<button className="rounded focus:outline-none">x</button>'
    const sinDeclarar = '<button className="rounded">x</button>'
    const declarado = '<button className="rounded focus-visible:ring-2 focus-visible:ring-focus">x</button>'
    expect(controlesSinFoco(apagado, ruta).map((h) => h.estado)).toEqual(['APAGADO'])
    expect(controlesSinFoco(sinDeclarar, ruta).map((h) => h.estado)).toEqual(['SIN DECLARAR'])
    expect(controlesSinFoco(declarado, ruta)).toEqual([])
  })

  /** Y que junte lo que aporta un `cn()` de dos partes, que es donde estaba el falso positivo. */
  it('junta las clases que llegan por una constante antes de decidir', () => {
    const ruta = join(SRC, 'shared', 'ui', 'Falso.tsx')
    const partido = [
      "const FOCO = 'focus-visible:ring-2 focus-visible:ring-focus'",
      "<button className={cn('rounded', FOCO)}>x</button>",
    ].join('\n')
    expect(controlesSinFoco(partido, ruta)).toEqual([])
  })
})

/**
 * Los dos sitios que llevan CONTORNO y no anillo, y por qué.
 *
 * Un anillo es una sombra. Una sombra no se pinta sobre una fila de tabla cuando los bordes están
 * colapsados, y una sombra hacia adentro queda debajo del fondo de los hijos opacos del elemento.
 * En los dos casos el foco se dibujaría a medias o no se dibujaría, y en los dos el contorno sí,
 * porque se pinta después. La revisión encontró el segundo caso: el panel de reportes había salido
 * con anillo hacia adentro y sus tarjetas lo tapaban.
 *
 * Se congelan los dos porque el criterio no se puede deducir del texto: hay que saber que los hijos
 * son opacos. Si aparece un tercero, esta lista obliga a decir cuál y por qué.
 */
describe('los sitios que llevan contorno en vez de anillo', () => {
  const CON_CONTORNO: Record<string, string> = {
    'shared/ui/DataTable.tsx': 'la fila clickeable: la tabla colapsa sus bordes y ahí una sombra no se pinta',
    'features/warehouse/pages/WarehouseReportsPage.tsx':
      'el panel de resultados: sus hijos son tarjetas opacas y taparían una sombra hacia adentro',
  }

  // Se afirma que el contorno esté, y no que el archivo no tenga anillos: los dos archivos tienen
  // además otros controles (el botón de reintentar, los de paginación) que sí van con anillo, y
  // están bien así. Lo que esta guarda ataja es que el sitio que necesita contorno lo pierda.
  it.each(Object.entries(CON_CONTORNO))('%s lleva contorno', (ruta) => {
    expect(readFileSync(join(SRC, ruta), 'utf8'), 'este sitio necesita contorno, no anillo').toMatch(
      /focus-visible:outline-2[\s\S]{0,140}focus-visible:outline-focus/,
    )
  })
})
