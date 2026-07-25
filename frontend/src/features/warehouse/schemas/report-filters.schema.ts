import { z } from 'zod'
import { todayIsoDate } from '../../../shared/utils/formatters'

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

/**
 * Filtros del reporte. Como en Existencias y Entradas, no es un form clásico:
 * tipa el estado local de la barra de corte y rango.
 *
 * A diferencia de los otros listados, los tres campos son OBLIGATORIOS en el
 * contrato, así que el estado admite fechas vacías (el usuario puede borrar el
 * input) pero la consulta no se dispara hasta que el rango está completo.
 */
export const reportFiltersSchema = z
  .object({
    cut: z.enum(['BY_UNIT', 'BY_PERIOD', 'BY_PRODUCT', 'BY_SUPPLIER']),
    dateFrom: z.string().regex(ISO_DATE),
    dateTo: z.string().regex(ISO_DATE),
  })
  .refine((value) => value.dateFrom <= value.dateTo, {
    message: 'La fecha "desde" no puede ser posterior a la fecha "hasta".',
    path: ['dateTo'],
  })

export type ReportFilters = z.infer<typeof reportFiltersSchema>

/** `true` si el rango está completo y en orden (condición para consultar). */
export function isReportRangeValid(filters: ReportFilters): boolean {
  return reportFiltersSchema.safeParse(filters).success
}

/** `true` si ambas fechas están cargadas pero invertidas. */
export function isReportRangeInverted(filters: ReportFilters): boolean {
  return !!filters.dateFrom && !!filters.dateTo && filters.dateFrom > filters.dateTo
}

/**
 * `true` si falta alguna de las dos fechas. Es un caso DISTINTO del rango
 * invertido y hay que avisarlo igual: sin aviso, borrar una fecha deja en
 * pantalla el reporte del rango anterior mientras los inputs muestran otro.
 */
export function isReportRangeIncomplete(filters: ReportFilters): boolean {
  return !filters.dateFrom || !filters.dateTo
}

/** Primer día del mes en curso como `YYYY-MM-DD`, derivado de la fecha local. */
export function currentMonthStart(): string {
  return `${todayIsoDate().slice(0, 8)}01`
}

/**
 * Estado inicial: consumo por unidad de flota en el mes en curso (del 1° a hoy),
 * el mismo corte mensual que usan los indicadores de Existencias.
 */
export function defaultReportFilters(): ReportFilters {
  return { cut: 'BY_UNIT', dateFrom: currentMonthStart(), dateTo: todayIsoDate() }
}
