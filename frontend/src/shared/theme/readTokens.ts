/**
 * Extrae los tokens de color del CSS global, leyéndolo como TEXTO.
 *
 * Se parsea el archivo en vez de mirar los estilos calculados porque en el
 * entorno de pruebas no hay ninguno: `vitest.config.ts` declara `css: false` y
 * ningún test importa `index.css`, así que una clase de Tailwind no produce
 * ningún color. Medir el texto del archivo es lo único que mide algo acá.
 *
 * La función es pura y recibe el CSS: quien la llama decide de dónde sale. El
 * test lo lee con `node:fs`, como ya hace `internalNote-guard.test.ts`. NO sirve
 * traerlo con `?raw`: bajo `css: false` ese import devuelve la cadena vacía,
 * medido.
 */

/**
 * Los pares `nombre -> #rgb` de tres a ocho dígitos declarados dentro del
 * PRIMER bloque `@theme`. La forma exacta la valida quien los use: acá se
 * extraen, y `parseHex` rechaza lo que no sepa medir.
 * El nombre viene sin el prefijo `--color-`: `fg`, `surface`, `danger-soft`.
 *
 * Lee un solo bloque a propósito, para medir un tema y no una mezcla de dos. Los valores
 * del modo oscuro viven en su propio selector desde el PR del tema oscuro y se leen con
 * `parseThemeOverrides`, acá abajo; la prueba de contraste exige que los temas publicados
 * sean exactamente dos y mide los dos.
 *
 * Acepta el `@theme` con cualquier cantidad de modificadores a propósito: si alguien cambia
 * la declaración, esta función tiene que seguir leyéndola para que la prueba de
 * contraste siga midiendo en vez de quedarse sin nada que medir. (Si el patrón
 * no encuentra el bloque, esto LANZA y el archivo entero falla al importar: se
 * rompe fuerte, no calla. Lo que la tolerancia evita es tener que tocar dos
 * archivos por un cambio de modificador.)
 *
 * El modificador se acepta con un comodín y no por su nombre porque Tailwind
 * escanea este archivo buscando clases: nombrarlo acá crea de verdad la utilidad
 * homónima en el CSS de producción. Medido: la primera versión enumeraba los
 * modificadores y agregó una regla muerta al bundle.
 *
 * Acepta VARIOS a propósito, y eso no relaja ninguna red: la combinación
 * peligrosa (`static inline`, que publica los tokens y a la vez hornea el
 * literal en cada utilidad) la ataja la guarda de efecto de la prueba, que exige
 * que la utilidad referencie la variable. Antes de este cambio lo único que la
 * atajaba era que esta función lanzara, que es una barrera accidental: el
 * arreglo natural de ese error confuso la desactivaba.
 */
export function parseThemeColors(css: string): Record<string, string> {
  const block = /@theme(?:\s+[a-z]+)*\s*\{([\s\S]*?)\n\}/.exec(css)
  if (!block) {
    throw new Error('No se encontró un bloque @theme en el CSS recibido.')
  }
  const colors: Record<string, string> = {}
  for (const [, name, value] of block[1].matchAll(
    /--color-([a-z0-9-]+)\s*:\s*(#[0-9a-fA-F]{3,8})\s*;/g,
  )) {
    colors[name] = value
  }
  return colors
}

/**
 * Los mismos pares, pero de un bloque que redefine el tema con un selector, como el del
 * modo oscuro. Se lee aparte de `parseThemeColors` a propósito: aquella mide LA
 * DECLARACIÓN del tema y esta mide una REDEFINICIÓN, y mezclarlas devolvería una paleta
 * que no es la de ningún tema.
 *
 * El selector se pasa entero y se escapa, así que el día que haya un tercer tema (alto
 * contraste, por ejemplo) esta función sirve igual sin tocarla.
 */
export function parseThemeOverrides(css: string, selector: string): Record<string, string> {
  const escapado = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const bloque = new RegExp(escapado + '\\s*\\{([\\s\\S]*?)\\n\\}').exec(css)
  if (!bloque) {
    throw new Error(`No se encontró un bloque ${selector} en el CSS recibido.`)
  }
  const colors: Record<string, string> = {}
  for (const [, name, value] of bloque[1].matchAll(
    /--color-([a-z0-9-]+)\s*:\s*(#[0-9a-fA-F]{3,8})\s*;/g,
  )) {
    colors[name] = value
  }
  return colors
}
