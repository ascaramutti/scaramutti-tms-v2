import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { currentMonthStart, defaultReportFilters, isReportRangeIncomplete } from './report-filters.schema'

/**
 * El rango que el reporte propone al abrirse va del 1° del mes a hoy, y ese "hoy" es el día que
 * es en Lima, no el del navegador de quien mira: el reporte es de la operación peruana.
 *
 * Hacen falta DOS bordes, y no uno. En el borde del día (02:30 UTC del 25 de agosto) Lima está
 * en el 24 y Tokio, donde corre esta suite, en el 25: ahí discrimina el fin del rango. Pero las
 * dos zonas siguen en el mismo MES, así que el arranque del mes da igual con cualquiera de las
 * dos y ese caso no mediría nada. Para el arranque hace falta el borde de mes (02:30 UTC del 1°
 * de septiembre), donde Lima sigue en agosto y Tokio ya pasó a septiembre.
 */
describe('los filtros por defecto del reporte, en el borde del día', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-25T02:30:00Z'))
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('el rango por defecto termina hoy en Lima, no en el día del navegador', () => {
    expect(defaultReportFilters()).toEqual({
      cut: 'BY_UNIT',
      dateFrom: '2026-08-01',
      dateTo: '2026-08-24',
    })
  })
})

describe('los filtros por defecto del reporte, en el borde del mes', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-09-01T02:30:00Z'))
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('el mes en curso todavía es agosto, que es el mes de Lima', () => {
    expect(currentMonthStart()).toBe('2026-08-01')
  })

  it('el rango por defecto queda entero dentro del mes de Lima', () => {
    expect(defaultReportFilters()).toEqual({
      cut: 'BY_UNIT',
      dateFrom: '2026-08-01',
      dateTo: '2026-08-31',
    })
  })
})

describe('isReportRangeIncomplete', () => {
  it('un rango con las dos puntas está completo', () => {
    expect(isReportRangeIncomplete({ cut: 'BY_UNIT', dateFrom: '2026-08-01', dateTo: '2026-08-24' })).toBe(false)
  })

  it('falta una punta y el rango está incompleto', () => {
    expect(isReportRangeIncomplete({ cut: 'BY_UNIT', dateFrom: '2026-08-01', dateTo: '' })).toBe(true)
  })
})
