import type { WarehouseReportResponse } from '../../../api'
import { buildCsv, type CsvCell } from '../../../shared/utils/csv'
import { reportCutMeta } from './reportCuts'

/**
 * Arma el CSV del reporte: encabezado, una fila por fila del backend y una fila
 * final de totales.
 *
 * Los montos van en columnas separadas por moneda y sin convertir (RN-WH7), y
 * los totales son los que devolvió el backend, no una suma local: si alguna vez
 * difieren, el archivo tiene que mostrar el dato del sistema, no el nuestro.
 */
export function buildReportCsv(report: WarehouseReportResponse): string {
  const meta = reportCutMeta(report.cut)
  const rows: CsvCell[][] = [
    ['Etiqueta', 'Detalle', capitalize(meta.countLabel), 'Monto PEN', 'Monto USD'],
    ...report.rows.map((row) => [
      row.label,
      row.detail,
      row.count,
      row.amountPEN,
      row.amountUSD,
    ]),
    ['TOTAL', null, report.totalCount, report.totalPEN, report.totalUSD],
  ]
  return buildCsv(rows)
}

/** Nombre del archivo exportado: corte y rango, para no pisar descargas previas. */
export function reportCsvFilename(report: WarehouseReportResponse): string {
  const meta = reportCutMeta(report.cut)
  return `almacen-reporte-${meta.slug}-${report.dateFrom}_${report.dateTo}.csv`
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1)
}
