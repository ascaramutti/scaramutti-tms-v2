/**
 * Convierte los `oklch()` de la paleta de Tailwind a sRGB.
 *
 * Existe para que los valores de los tokens tengan una SEGUNDA fuente de verdad.
 * Sin esto, el literal congelado de la prueba y el CSS son el mismo dato escrito
 * dos veces: cambiar los dos a la vez pasa en verde, y el comentario de paleta
 * de cada línea queda mintiendo sin que nada lo note. Medido antes de escribir
 * esto: pasar un token del valor de Tailwind 4 al de Tailwind 3 no lo mataba
 * ninguna prueba.
 *
 * La fuente es el paquete instalado, o sea la versión que fija el lockfile. Si
 * un upgrade de Tailwind mueve un color de la paleta, esta comparación lo dice.
 *
 * ⚠️ Este módulo lee del disco y SOLO corre en Node: es para las pruebas, no
 * para la aplicación. Nada de `src/features` ni de `src/shared/ui` debe
 * importarlo, y conviene saber CÓMO falla si alguien lo hace, porque no es como
 * uno esperaría: medido, el typecheck y el build salen los dos en 0, Vite
 * externaliza `node:fs` con un warning, el módulo entra al bundle y revienta en
 * el navegador. O sea que CI queda verde y el fallo aparece en runtime. La única
 * barrera hoy es esta advertencia; si el módulo crece de uso, conviene una regla
 * de lint que la haga mecánica.
 *
 * Vive acá y no junto a la prueba porque el conversor va a hacer falta de nuevo
 * al elegir los valores del modo oscuro, y ahí conviene que sea código con
 * nombre.
 */
import { readFileSync } from 'node:fs'

/** Las tres componentes sRGB, de 0 a 255. */
export type Rgb = readonly [number, number, number]

/**
 * OKLCH a sRGB, por el camino de la especificación: OKLCH a OKLab, a LMS, a RGB
 * lineal, y de ahí a sRGB con la codificación gamma. Las constantes son las de
 * la matriz inversa de OKLab; no son ajustables.
 *
 * El redondeo es a entero más cercano sobre el canal de 0 a 255, que es lo que
 * hace un navegador al pintar. Los valores fuera del gamut se recortan al rango
 * antes de redondear.
 */
export function oklchToRgb(lightness: number, chroma: number, hue: number): Rgb {
  if (![lightness, chroma, hue].every(Number.isFinite)) {
    throw new Error('oklchToRgb: cada componente tiene que ser un número finito.')
  }
  if (lightness > 1) {
    throw new Error(
      `oklchToRgb: la luminosidad va de 0 a 1 y llegó ${lightness}. En theme.css se ` +
        'escribe como porcentaje, así que hay que dividirla por 100 antes de pasarla.',
    )
  }
  const rad = (hue * Math.PI) / 180
  const a = chroma * Math.cos(rad)
  const b = chroma * Math.sin(rad)

  const l = (lightness + 0.3963377774 * a + 0.2158037573 * b) ** 3
  const m = (lightness - 0.1055613458 * a - 0.0638541728 * b) ** 3
  const s = (lightness - 0.0894841775 * a - 1.291485548 * b) ** 3

  const lineal = [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ]

  return lineal.map((canal) => {
    const acotado = Math.min(1, Math.max(0, canal))
    const codificado = acotado <= 0.0031308 ? 12.92 * acotado : 1.055 * acotado ** (1 / 2.4) - 0.055
    return Math.round(255 * codificado)
  }) as unknown as Rgb
}

/** `#rrggbb` en minúsculas, que es como se escriben los tokens. */
export function toHex(rgb: Rgb): string {
  return '#' + rgb.map((c) => c.toString(16).padStart(2, '0')).join('')
}

/**
 * La paleta del Tailwind instalado, como `nombre -> #rrggbb`. El nombre es el de
 * la escala (`slate-700`, `red-600`), sin el prefijo `--color-`.
 */
export function readTailwindPalette(rutaThemeCss: string): Record<string, string> {
  const css = readFileSync(rutaThemeCss, 'utf8')
  const paleta: Record<string, string> = {}
  for (const [, nombre, l, c, h] of css.matchAll(
    /--color-([a-z]+-\d{2,3})\s*:\s*oklch\(\s*([\d.]+)%\s+([\d.]+)\s+([\d.]+)\s*\)/g,
  )) {
    paleta[nombre] = toHex(oklchToRgb(Number(l) / 100, Number(c), Number(h)))
  }
  for (const [, nombre, valor] of css.matchAll(/--color-(white|black)\s*:\s*(#[0-9a-f]{3,6})/g)) {
    paleta[nombre] = valor.length === 4 ? '#' + [...valor.slice(1)].map((d) => d + d).join('') : valor
  }
  return paleta
}
