import { readdirSync, readFileSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * La guarda del nombre completo: lo compone el servidor (nombres, paterno y materno si lo tiene,
 * recortado) y la SPA lo muestra como llega en `fullName`. Armarlo a mano, o unir solo los apellidos
 * ("Torres null" sin materno), sería otra copia de la regla, sin test contra la del servidor. Mide el
 * texto por línea: se le escapan, entre otras, una composición en dos líneas o con `join` o `concat`.
 */

const NOMBRES = /\bfirstName\b/
const APELLIDO = /\b(?:lastName|paternalLastName|maternalLastName)\b/
const PATERNO = /\bpaternalLastName\b/
const MATERNO = /\bmaternalLastName\b/

/**
 * Los huecos de una plantilla o de un texto de JSX: un `{...}` sin delante un `=` (el valor de un
 * atributo) ni un `(` o un `[` pegados a un nombre o a un cierre (una llamada, una firma, un índice); un
 * paréntesis del texto sí cuenta. Prefiere marcar de más, en rojo: una lista de objetos en una línea.
 */
const HUECO = /(?<!=\s*|[\w$)\]][([]\s*)\{([^{}]*)\}/g

/** Una parte de cada lado en huecos distintos, o las dos en una línea con un `+`. */
function une(linea: string, uno: RegExp, otro: RegExp): boolean {
  if (!uno.test(linea) || !otro.test(linea)) return false
  if (linea.includes('+')) return true
  const huecos = [...linea.matchAll(HUECO)].map((hueco) => hueco[1])
  return huecos.some((con, i) => uno.test(con) && huecos.some((resto, j) => j !== i && otro.test(resto)))
}

/** Los nombres con un apellido, o los dos apellidos entre sí. */
function compone(linea: string): boolean {
  return une(linea, NOMBRES, APELLIDO) || une(linea, PATERNO, MATERNO)
}

const SRC = join(import.meta.dirname, '..')

/** Fuera del recorrido: el cliente generado y los tests, cuyos simulacros componen como el servidor. */
function deLaApp(ruta: string): boolean {
  return !ruta.startsWith('api/') && !ruta.startsWith('test/') && !/\.test\.tsx?$/.test(ruta)
}

function archivos(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entrada) => {
    const ruta = join(dir, entrada.name)
    if (entrada.isDirectory()) return archivos(ruta)
    return /\.tsx?$/.test(entrada.name) ? [relative(SRC, ruta).split(sep).join('/')] : []
  })
}

const recorridos = () => archivos(SRC).filter(deLaApp)

describe('el nombre completo lo compone el servidor', () => {
  it('ningún archivo de la app lo arma con sus partes', () => {
    const conComposicion = recorridos().flatMap((ruta) =>
      readFileSync(join(SRC, ruta), 'utf8')
        .split('\n')
        .flatMap((linea, i) => (compone(linea) ? [`${ruta}:${i + 1}`] : [])),
    )
    expect(conComposicion).toEqual([])
  })

  it('el recorrido lee las pantallas que lo armaban y deja fuera el cliente y los tests', () => {
    // Sin esto, un recorrido que no lee nada dejaría la lista vacía y la guarda pasaría sin mirar.
    const leidos = recorridos()
    expect(leidos).toEqual(
      expect.arrayContaining([
        'features/workers/pages/WorkerDetailPage.tsx',
        'features/workers/pages/WorkerEditPage.tsx',
        'features/workers/pages/WorkerCreatePage.tsx',
        'features/workers/components/WorkerStatusDialog.tsx',
      ]),
    )
    expect(archivos(SRC).filter((ruta) => !deLaApp(ruta))).toEqual(
      expect.arrayContaining(['api/types.gen.ts', 'test/mocks/handlers/workers.ts', 'shared/fullName.unico.test.ts']),
    )
  })

  it.each([
    'title={`${data.firstName} ${data.lastName}`}',
    'toast.success(`Se dio de alta a ${created.firstName} ${created.lastName}.`)',
    '`${worker.firstName} ${worker.paternalLastName} ${worker.maternalLastName ?? \'\'}`',
    '`${worker.paternalLastName}, ${worker.firstName}`',
    '<span>{worker.firstName} {worker.maternalLastName}</span>',
    '<dd>Nombre: {worker.firstName}, {worker.paternalLastName}</dd>',
    '<p>Responsable ({worker.firstName} {worker.paternalLastName})</p>',
    "worker.firstName + ' ' + worker.lastName",
    "(worker.firstName ?? '') + ' ' + (worker.paternalLastName ?? '')",
    '`${worker.paternalLastName} ${worker.maternalLastName}`',
    '<dd>{worker.maternalLastName}, {worker.paternalLastName}</dd>',
  ])('reconoce una composición: %s', (linea) => {
    expect(compone(linea)).toBe(true)
  })

  it.each([
    '<PersonNameRow firstName={worker.firstName} paternalLastName={worker.paternalLastName} />',
    'export function PersonNameRow({ firstName, paternalLastName, maternalLastName, className }: Props) {',
    'function Fila({ firstName, paternalLastName }: { firstName: string; paternalLastName: string }) {',
    'type Partes = { firstName: string } & { paternalLastName: string }',
    "f({ firstName: 'Ana' }, { lastName: 'Torres' })",
    "{ firstName: 'Ana', paternalLastName: 'Torres', maternalLastName: null }",
    'title={data.fullName}',
    '`${uno.firstName} y ${otro.firstName}`',
    '`${uno.paternalLastName} y ${otro.paternalLastName}`',
    '`${uno.maternalLastName} y ${otro.maternalLastName}`',
  ])('no confunde con una composición: %s', (linea) => {
    expect(compone(linea)).toBe(false)
  })
})
