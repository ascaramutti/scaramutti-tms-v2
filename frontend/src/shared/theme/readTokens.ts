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
 * Lee un solo bloque a propósito, para medir un tema y no una mezcla de dos.
 * Cuando existan los valores del modo oscuro, van en su propio selector y hay
 * que leerlos aparte; la prueba de contraste tiene un caso que falla el día que
 * aparezcan, justamente para que nadie lo dé por resuelto.
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
