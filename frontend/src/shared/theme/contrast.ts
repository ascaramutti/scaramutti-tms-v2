/**
 * Contraste WCAG 2.1 entre dos colores sRGB.
 *
 * Vive en `src/` y no en el archivo de prueba porque la regla que mide (que un
 * texto se lea sobre su fondo) es del sistema de diseño, no del test. Cuando el
 * modo oscuro entre, el mismo cálculo va a medir sus pares.
 *
 * Solo acepta `#rrggbb` y `#rgb`: los tokens del tema se escriben así a
 * propósito, para que esta medición no dependa de convertir espacios de color.
 */

export type Rgb = readonly [number, number, number]

/** `#rrggbb` o `#rgb` a sus tres componentes 0-255. */
export function parseHex(color: string): Rgb {
  const trimmed = color.trim()
  if (!trimmed.startsWith('#')) {
    throw new Error(`Color no reconocido: "${color}". Se esperaba #rrggbb o #rgb.`)
  }
  const raw = trimmed.slice(1)
  const full = raw.length === 3 ? [...raw].map((c) => c + c).join('') : raw
  if (!/^[0-9a-fA-F]{6}$/.test(full)) {
    throw new Error(`Color no reconocido: "${color}". Se esperaba #rrggbb o #rgb.`)
  }
  return [
    Number.parseInt(full.slice(0, 2), 16),
    Number.parseInt(full.slice(2, 4), 16),
    Number.parseInt(full.slice(4, 6), 16),
  ] as const
}

/**
 * Luminancia relativa (WCAG 2.1, definición 'relative luminance').
 * El canal se linealiza antes de pesarlo: el 0.04045 y el exponente 2.4 son de
 * la especificación, no una aproximación.
 */
export function relativeLuminance(rgb: Rgb): number {
  const [r, g, b] = rgb.map((channel) => {
    const c = channel / 255
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  }) as unknown as Rgb
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * Cociente de contraste entre dos colores, de 1 (idénticos) a 21 (negro sobre
 * blanco). El orden de los argumentos no cambia el resultado.
 */
export function contrastRatio(foreground: string, background: string): number {
  const a = relativeLuminance(parseHex(foreground))
  const b = relativeLuminance(parseHex(background))
  const [lighter, darker] = a >= b ? [a, b] : [b, a]
  return (lighter + 0.05) / (darker + 0.05)
}
