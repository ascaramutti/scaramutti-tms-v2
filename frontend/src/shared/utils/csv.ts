/** Celda de una fila del CSV. `null`/`undefined` se serializan como campo vacío. */
export type CsvCell = string | number | null | undefined

/** Byte order mark UTF-8: sin él, Excel abre el archivo en ANSI y rompe tildes y "ñ". */
const UTF8_BOM = '﻿'

/** Fin de línea CRLF, como manda RFC 4180 (y como espera Excel en Windows). */
const CRLF = '\r\n'

/**
 * Un campo que empieza así hace que Excel interprete el texto como fórmula. Las
 * etiquetas del reporte vienen de datos cargados por usuarios (nombres de
 * productos y proveedores), así que se neutralizan. Los espacios y tabs
 * iniciales entran en el patrón porque Excel los ignora antes de evaluar.
 */
const FORMULA_START = /^\s*[=+\-@]/

function escapeCell(cell: CsvCell, separator: string): string {
  if (cell === null || cell === undefined) return ''
  // Los números van crudos: el CSV es dato para recalcular, no para leer. El
  // formato de pantalla (separador de miles, símbolo de moneda) lo rompería.
  if (typeof cell === 'number') return String(cell)

  const text = FORMULA_START.test(cell) ? `'${cell}` : cell
  const needsQuotes =
    text.includes(separator) || text.includes('"') || text.includes('\n') || text.includes('\r')
  return needsQuotes ? `"${text.replaceAll('"', '""')}"` : text
}

/**
 * Serializa una matriz de celdas a CSV (RFC 4180) listo para abrir en Excel.
 *
 * El separador por defecto es la coma: en es-PE el decimal es el punto, así que
 * la coma queda libre como separador de lista. Los campos que la contienen se
 * entrecomillan, y las comillas internas se duplican.
 */
export function buildCsv(
  rows: readonly (readonly CsvCell[])[],
  options: { separator?: string } = {},
): string {
  const separator = options.separator ?? ','
  const body = rows
    .map((row) => row.map((cell) => escapeCell(cell, separator)).join(separator))
    .join(CRLF)
  return `${UTF8_BOM}${body}${CRLF}`
}

/** Envuelve el CSV en un `Blob` con el tipo y charset que espera el navegador. */
export function csvBlob(content: string): Blob {
  return new Blob([content], { type: 'text/csv;charset=utf-8' })
}
