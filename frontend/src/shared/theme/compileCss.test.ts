import { mkdtempSync, mkdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterAll, describe, expect, it } from 'vitest'
import { compileGlobalCss } from './compileCss'

/**
 * El helper es el sustrato de las guardas de declaración: si compila mal, esas
 * guardas miden un modelo equivocado y quedan verdes por el motivo equivocado.
 *
 * Se compila un proyecto de juguete y no el real, para poder ejercitar la rama
 * que el `index.css` de hoy no usa: la que resuelve un `@import` relativo. Esa
 * rama sostiene la mitad de la promesa del forzador del modo oscuro, y sin este
 * caso está muerta en la suite.
 */
const raiz = mkdtempSync(join(tmpdir(), 'tema-'))
mkdirSync(join(raiz, 'src'))
// El helper resuelve `@import "tailwindcss"` contra el node_modules de la raíz
// que se le pasa. Se enlaza el real para no tener que cambiar el helper por una
// prueba, y para que lo que se compile acá sea el mismo Tailwind del proyecto.
symlinkSync(join(process.cwd(), 'node_modules'), join(raiz, 'node_modules'), 'dir')

function escribir(archivo: string, contenido: string) {
  writeFileSync(join(raiz, 'src', archivo), contenido, 'utf8')
}

afterAll(() => rmSync(raiz, { recursive: true, force: true }))

describe('compileGlobalCss', () => {
  it('devuelve CSS compilado y no el archivo fuente', async () => {
    escribir('index.css', '@import "tailwindcss";\n@theme static {\n  --color-x: #010203;\n}\n')
    const { css } = await compileGlobalCss(raiz)
    expect(css).not.toMatch(/@theme|@import/)
    expect(css).toMatch(/--color-x:\s*#010203/)
  })

  it('resuelve un @import relativo, así que una hoja aparte entra por acá', async () => {
    // Es la rama que el index.css real no ejercita. Si dejara de resolver, un
    // segundo tema declarado en su propio archivo se volvería invisible para las
    // guardas, que es exactamente lo que prometen atajar.
    escribir('extra.css', '[data-theme="dark"] {\n  --color-x: #0b1626;\n}\n')
    escribir('index.css', '@import "tailwindcss";\n@import "./extra.css";\n@theme static {\n  --color-x: #010203;\n}\n')
    const { css } = await compileGlobalCss(raiz)
    expect(css).toMatch(/\[data-theme=?"?dark"?\]/)
    expect(css).toMatch(/#0b1626/)
  })

  it('expone las fuentes de escaneo tal como las lee Tailwind, con su base', async () => {
    escribir('index.css', '@import "tailwindcss";\n@source not "./nada/**";\n@theme static {\n  --color-x: #010203;\n}\n')
    const { fuentes } = await compileGlobalCss(raiz)
    const negadas = fuentes.filter((f) => f.negated)
    expect(negadas.map((f) => f.pattern)).toEqual(['./nada/**'])
    // La base importa tanto como el patrón: un patrón correcto con la base
    // equivocada excluye otra carpeta y nadie se entera.
    expect(negadas[0].base).toBe(join(raiz, 'src'))
  })

  it('emite las utilidades de las clases que encuentra, y no solo las variables', async () => {
    escribir('index.css', '@import "tailwindcss";\n@theme static {\n  --color-x: #010203;\n}\n')
    escribir('uso.tsx', 'export const A = () => <b className="bg-x" />\n')
    const { css } = await compileGlobalCss(raiz)
    expect(css).toMatch(/\.bg-x\s*\{\s*background-color:\s*var\(--color-x\)/)
  })
})
