/**
 * Compila el CSS global con el Tailwind instalado y devuelve la hoja resultante.
 *
 * Existe porque las guardas que leían el TEXTO de `index.css` afirmaban el
 * símbolo y no su efecto, y cada arreglo movía el símbolo un paso. Medido: el
 * tema oscuro en un archivo aparte, o anidado en una sola línea, se publicaba
 * con la suite entera en verde; y en la dirección opuesta, un comentario en
 * castellano que usara la palabra del selector ponía la suite en rojo con el
 * código intacto. Sobre el CSS compilado las dos cosas se resuelven solas: los
 * comentarios no sobreviven a la compilación, y las utilidades salen con la
 * forma que van a tener.
 *
 * ⚠️ Solo corre en Node: lee del disco y arrastra `tailwindcss` entero. Es para
 * las pruebas, no para la aplicación, y es el MÁS caro de los dos módulos así:
 * medido, importarlo desde una pantalla suma unos 10 kB al JS y 86 selectores
 * muertos al CSS, porque al entrar el paquete al grafo de módulos el escáner lee
 * su `dist`. `palette.ts` cuesta 850 bytes. La guarda que impide el import está
 * en `theme-contrast.test.ts`.
 *
 * Y sobre el alcance, con precisión: esto compila `index.css` con el compilador
 * de Tailwind y la lista de clases que el escáner encuentra en `src/`. NO es el
 * bundle que Vite publica: que el pipeline invoque a este compilador no lo
 * afirma nada acá. Lo que sí vale es que las directivas y los comentarios no
 * sobreviven, y que las utilidades salen con la forma que van a tener.
 */
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { Scanner } from '@tailwindcss/oxide'
import { compile } from 'tailwindcss'

/**
 * El CSS que Tailwind emite para `index.css`, con la lista de clases que se le
 * pase (vacía alcanza para ver las variables del tema, que `@theme static`
 * emite siempre).
 */
export interface CssCompilado {
  /** La hoja que Tailwind emite. */
  css: string
  /**
   * Las fuentes de escaneo tal como TAILWIND las entiende, no como las lea un
   * regex nuestro: cada `@source` con su patrón y si está negado. Es lo que
   * permite afirmar sobre la directiva de exclusión sin volver a parsear el
   * archivo por nuestra cuenta.
   */
  fuentes: { base: string; pattern: string; negated: boolean }[]
}

export async function compileGlobalCss(
  raizFrontend: string,
  clasesExtra: string[] = [],
): Promise<CssCompilado> {
  const rutaCss = join(raizFrontend, 'src', 'index.css')
  const compilador = await compile(readFileSync(rutaCss, 'utf8'), {
    base: dirname(rutaCss),
    // Resuelve `@import "tailwindcss"` y cualquier `@import` RELATIVO desde
    // `index.css`. Ojo con el alcance: una hoja que se importe desde un módulo
    // TS en vez de desde acá no pasa por este compilador. Contra eso hay una
    // guarda aparte, que exige que `index.css` sea la única del árbol.
    loadStylesheet: async (id: string, base: string) => {
      const ruta = id.startsWith('.')
        ? resolve(base, id)
        : join(raizFrontend, 'node_modules', id, 'index.css')
      return { path: ruta, base: dirname(ruta), content: readFileSync(ruta, 'utf8') }
    },
  })
  // El escáner necesita una fuente positiva explícita: `compilador.sources` trae
  // solo la negada de `@source not`, y sin nada positivo el barrido da cero.
  const escaner = new Scanner({
    sources: [
      { base: join(raizFrontend, 'src'), pattern: '**/*', negated: false },
      ...compilador.sources,
    ],
  })
  return {
    css: compilador.build([...escaner.scan(), ...clasesExtra]),
    fuentes: compilador.sources,
  }
}
