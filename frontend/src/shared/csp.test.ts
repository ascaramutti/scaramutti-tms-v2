import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { z } from 'zod'

/**
 * Nada ejecuta la política antes del navegador: si una copia se separa, o el script del tema
 * cambia sin su huella, el CI pasaría igual y en ese entorno cada carga empezaría en claro para
 * corregir al montar.
 */
const COPIAS = {
  imagen: 'nginx.conf',
  staging: join('..', 'deploy', 'staging', 'nginx-staging.conf'),
  produccion: join('..', '.github', 'workflows', 'deploy-frontend.yml'),
} as const

// Anclada a la línea: una cabecera comentada no la manda nginx.
const CABECERA = /^[ \t]*add_header Content-Security-Policy "([^"]*)" always;$/m

function leer(ruta: string) {
  return readFileSync(join(process.cwd(), ruta), 'utf8')
}

/** El cuerpo del `location /`; ninguna copia anida llaves adentro. */
function locationRaiz(conf: string) {
  const inicio = conf.indexOf('location / {')
  return inicio < 0 ? '' : conf.slice(inicio, conf.indexOf('}', inicio))
}

function politicaDe(ruta: string) {
  return CABECERA.exec(locationRaiz(leer(ruta)))?.[1]
}

function directivas(politica: string) {
  return politica
    .split(';')
    .map((d) => d.trim().split(/\s+/))
    .filter(([nombre]) => nombre)
    // El navegador no distingue mayúsculas en los nombres.
    .map(([nombre, ...fuentes]) => ({ nombre: nombre.toLowerCase(), fuentes }))
}

/** Las fuentes de una directiva; el navegador ignora las repeticiones, y una prueba las prohíbe. */
function fuentesDe(politica: string, nombre: string) {
  return directivas(politica).find((d) => d.nombre === nombre)?.fuentes
}

const politica = politicaDe(COPIAS.imagen) ?? ''

describe('la Content-Security-Policy de nginx', () => {
  it.each(Object.entries(COPIAS))('%s la manda una sola vez, desde `location /` y con `always`', (_, ruta) => {
    // Una sola en el archivo: puesta también en el `server` no llega a ningún `location` que
    // declare sus propias cabeceras, y en otro `location` no cubriría el documento.
    expect(leer(ruta).split('Content-Security-Policy').length - 1).toBe(1)
    expect(politicaDe(ruta), 'la cabecera tiene que estar dentro de `location /`').toBeDefined()
  })

  it.each([
    ['staging', COPIAS.staging],
    ['produccion', COPIAS.produccion],
  ])('%s lleva la misma política que la imagen', (_, ruta) => {
    expect(politicaDe(ruta)).toBe(politica)
  })

  it('`script-src` permite el origen y la huella de cada script en línea del `index.html`, nada más', () => {
    const html = leer('index.html')
    const enLinea = [...html.matchAll(/<script(?![^>]*\ssrc=)[^>]*>([\s\S]*?)<\/script>/gi)].map((m) => m[1])
    expect(enLinea.length, 'el `index.html` tiene que traer el script del tema en línea').toBeGreaterThan(0)
    const huellas = enLinea.map((s) => `'sha256-${createHash('sha256').update(s, 'utf8').digest('base64')}'`)
    expect(
      fuentesDe(politica, 'script-src'),
      'el script en línea del `index.html` cambió (comentarios incluidos): la huella nueva va en las tres copias de la política',
    ).toEqual(["'self'", ...huellas])
    const sinComentarios = html.replace(/<!--[\s\S]*?-->/g, '')
    expect(sinComentarios, 'un manejador en línea no tiene huella que lo permita').not.toMatch(/<[^>]*\son[a-z]+\s*=/i)
  })

  it('`script-src` no abre los scripts en línea ni la evaluación de código', () => {
    const fuentes = fuentesDe(politica, 'script-src') ?? []
    expect(fuentes).not.toContain("'unsafe-inline'")
    expect(fuentes).not.toContain("'unsafe-eval'")
    // `script-src-elem` y `script-src-attr` le ganan a `script-src` en lo suyo.
    const nombres = directivas(politica).map((d) => d.nombre)
    expect(nombres.filter((n) => n.startsWith('script-src'))).toEqual(['script-src'])
    expect(new Set(nombres).size, 'el navegador usa la primera de dos directivas iguales').toBe(nombres.length)
  })

  it('las directivas que cierran las salidas quedan cerradas', () => {
    expect(
      Object.fromEntries(
        ['default-src', 'connect-src', 'img-src', 'object-src', 'base-uri', 'form-action', 'frame-ancestors'].map(
          (n) => [n, fuentesDe(politica, n)],
        ),
      ),
    ).toEqual({
      'default-src': ["'self'"],
      'connect-src': ["'self'"],
      'img-src': ["'self'"],
      'object-src': ["'none'"],
      'base-uri': ["'self'"],
      'form-action': ["'self'"],
      'frame-ancestors': ["'none'"],
    })
  })

  it('los únicos orígenes de afuera son los de Google Fonts, cada uno en su directiva', () => {
    // Una fuente sin comillas es un origen o un esquema (`*`, `https:`, `data:`, `blob:`).
    const externos = directivas(politica).flatMap(({ nombre, fuentes }) =>
      fuentes.filter((f) => !f.startsWith("'")).map((f) => `${nombre} ${f}`),
    )
    expect(externos).toEqual(['style-src https://fonts.googleapis.com', 'font-src https://fonts.gstatic.com'])
  })

  it("`'unsafe-inline'` va solo en los estilos", () => {
    const con = directivas(politica).filter(({ fuentes }) => fuentes.includes("'unsafe-inline'"))
    expect(con.map((d) => d.nombre)).toEqual(['style-src'])
  })

  it('no lleva `$`: la plantilla de producción pasa por una sustitución de variables al arrancar', () => {
    expect(politica).not.toContain('$')
  })
})

describe('zod con la CSP', () => {
  it('el módulo apaga la compilación de validaciones al importarse', async () => {
    expect(z.config().jitless).not.toBe(true)
    await import('./zodConfig')
    expect(z.config().jitless).toBe(true)

    // Y con eso zod ya no intenta compilar: es el intento lo que la CSP anota en la consola.
    const compilar = vi.spyOn(globalThis, 'Function')
    try {
      expect(z.object({ a: z.string() }).parse({ a: 'x' })).toEqual({ a: 'x' })
      expect(compilar).not.toHaveBeenCalled()
    } finally {
      compilar.mockRestore()
    }
  })

  it('`main.tsx` lo importa antes que cualquier otro módulo', () => {
    expect(/^import .*$/m.exec(leer(join('src', 'main.tsx')))?.[0]).toBe("import './shared/zodConfig'")
  })
})
