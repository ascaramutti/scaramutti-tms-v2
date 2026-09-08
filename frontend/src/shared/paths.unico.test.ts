import { readdirSync, readFileSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  CHANGE_PASSWORD_PATH,
  LOGIN_PATH,
  OPERATIONS_BASE,
  QUOTATIONS_BASE,
  SPA_BASE,
  WAREHOUSE_BASE,
} from './paths'

/**
 * La guarda que mantiene la centralización: las rutas se declaran en un solo
 * lugar y nadie más las escribe.
 *
 * Sin ella, la mudanza que sigue cambia los valores en `paths.ts` y deja atrás
 * cualquier literal que se haya colado después, con el agravante de que la suite
 * seguiría verde: un `to` con la ruta de almacén escrita a mano sigue navegando a
 * algo hasta el día del despliegue.
 *
 * Busca los VALORES de las constantes, no un texto fijo, así que sigue sirviendo
 * cuando cambien. Y los busca **entre comillas**: lo que se persigue es la ruta
 * escrita como dato, no la palabra suelta. Sin esa condición, el día que la base
 * pase a `/` la guarda buscaría una barra en todo el árbol y señalaría cada
 * archivo que importa algo.
 */

/** Los dos únicos archivos donde una ruta puede estar escrita, y por qué. */
const PERMITIDOS = [
  // Las declara: es la fuente.
  join('shared', 'paths.ts'),
  // Fija el contrato de URL escribiéndolo entero; es lo que hace verificable la
  // mudanza, así que tiene que nombrar los valores.
  join('shared', 'paths.rutas.test.ts'),
]

/**
 * La raíz queda afuera, valga la que valga la constante: `'/'` entre comillas
 * aparece en cualquier archivo que parta una cadena o declare la ruta raíz, y
 * buscarla daría ruido en vez de hallazgos. El día que la base sea `/`, quedan
 * afuera ella y cualquier otra que haya colapsado a lo mismo; las que nombran
 * cada módulo siguen adentro, que son las que importan.
 */
const VALORES = [
  SPA_BASE,
  LOGIN_PATH,
  CHANGE_PASSWORD_PATH,
  QUOTATIONS_BASE,
  WAREHOUSE_BASE,
  OPERATIONS_BASE,
].filter((valor) => valor !== '/')

const COMILLAS = ["'", '"', '`']

/**
 * Una ruta escrita como dato: abre comilla, viene el valor, y sigue la comilla de
 * cierre o una barra (para atrapar también las rutas más profundas escritas a
 * mano). Un `import './almacen'` no entra: entre la comilla y la barra hay un
 * punto.
 */
function escritaComoDato(contenido: string): boolean {
  return VALORES.some((valor) =>
    COMILLAS.some((comilla) =>
      contenido.includes(`${comilla}${valor}${comilla}`) ||
      contenido.includes(`${comilla}${valor}/`),
    ),
  )
}

const SRC = join(import.meta.dirname, '..')

function archivos(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entrada) => {
    const ruta = join(dir, entrada.name)
    if (entrada.isDirectory()) return archivos(ruta)
    return /\.tsx?$/.test(entrada.name) ? [ruta] : []
  })
}

function conRutasEscritas(): string[] {
  return archivos(SRC)
    .filter((ruta) => escritaComoDato(readFileSync(ruta, 'utf8')))
    .map((ruta) => relative(SRC, ruta).split(sep).join('/'))
}

describe('las rutas de la SPA viven en un solo lugar', () => {
  it('ningún archivo fuera de los dos declarados las escribe', () => {
    const permitidos = PERMITIDOS.map((ruta) => ruta.split(sep).join('/'))
    expect(conRutasEscritas().filter((ruta) => !permitidos.includes(ruta))).toEqual([])
  })

  it('el recorrido encuentra exactamente los dos declarados', () => {
    // Sin esto, un recorrido que no lee nada o un patrón que no matchea dejarían
    // la lista vacía y la guarda pasaría sin haber mirado un solo archivo.
    expect(conRutasEscritas().sort()).toEqual(
      PERMITIDOS.map((ruta) => ruta.split(sep).join('/')).sort(),
    )
  })
})
