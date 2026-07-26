import { describe, expect, it } from 'vitest'
import type { WarehouseReportResponse } from '../../../api'
import { buildReportCsv, reportCsvFilename } from './reportCsv'

const BOM = '﻿'

function report(overrides: Partial<WarehouseReportResponse> = {}): WarehouseReportResponse {
  return {
    cut: 'BY_UNIT',
    dateFrom: '2026-06-01',
    dateTo: '2026-06-30',
    rows: [
      { label: 'Tracto ABC-123', detail: null, count: 4, amountPEN: 1234.5, amountUSD: 0 },
      { label: 'Sin unidad asignada', detail: null, count: 1, amountPEN: 0, amountUSD: 320 },
    ],
    totalPEN: 1234.5,
    totalUSD: 320,
    totalCount: 5,
    ...overrides,
  }
}

/** Líneas del CSV sin el BOM ni la línea vacía final. */
function lines(csv: string): string[] {
  return csv.replace(BOM, '').trimEnd().split('\r\n')
}

describe('buildReportCsv', () => {
  it('encabeza con las columnas del reporte', () => {
    expect(lines(buildReportCsv(report()))[0]).toBe('Etiqueta,Detalle,Retiros,Monto PEN,Monto USD')
  })

  it('rotula la columna de conteo según el corte', () => {
    // En BY_PRODUCT el backend cuenta unidades retiradas, no movimientos.
    expect(lines(buildReportCsv(report({ cut: 'BY_PRODUCT' })))[0]).toContain(
      'Unidades retiradas',
    )
    expect(lines(buildReportCsv(report({ cut: 'BY_SUPPLIER' })))[0]).toContain('Facturas')
  })

  it('serializa una fila por cada fila del backend, en el mismo orden', () => {
    const rows = lines(buildReportCsv(report()))
    expect(rows[1]).toBe('Tracto ABC-123,,4,1234.5,0')
    expect(rows[2]).toBe('Sin unidad asignada,,1,0,320')
  })

  it('incluye el detalle cuando la fila lo trae', () => {
    const csv = buildReportCsv(
      report({
        cut: 'BY_PERIOD',
        rows: [{ label: 'Semana del 22/06', detail: '2026-06-22', count: 2, amountPEN: 10, amountUSD: 0 }],
      }),
    )
    expect(lines(csv)[1]).toBe('Semana del 22/06,2026-06-22,2,10,0')
  })

  it('escapa las etiquetas con comas y comillas', () => {
    const csv = buildReportCsv(
      report({
        rows: [
          { label: 'Repuestos "El Rápido", S.A.', detail: null, count: 1, amountPEN: 5, amountUSD: 0 },
        ],
      }),
    )
    expect(lines(csv)[1]).toBe('"Repuestos ""El Rápido"", S.A.",,1,5,0')
  })

  it('emite los montos sin símbolo de moneda ni separador de miles', () => {
    const csv = buildReportCsv(
      report({ rows: [{ label: 'X', detail: null, count: 1, amountPEN: 12345.67, amountUSD: 0 }] }),
    )
    expect(lines(csv)[1]).toContain('12345.67')
    expect(csv).not.toContain('S/')
    expect(csv).not.toContain('12,345')
  })

  it('cierra con la fila de totales del backend', () => {
    const rows = lines(buildReportCsv(report()))
    expect(rows[rows.length - 1]).toBe('TOTAL,,5,1234.5,320')
  })

  it('usa los totales del backend aunque no coincidan con la suma de las filas', () => {
    // El front no recalcula agregados: el dato del sistema manda.
    const rows = lines(buildReportCsv(report({ totalPEN: 999, totalUSD: 111, totalCount: 42 })))
    expect(rows[rows.length - 1]).toBe('TOTAL,,42,999,111')
  })

  it('de un reporte vacío emite solo el encabezado y los totales en cero', () => {
    const rows = lines(
      buildReportCsv(report({ rows: [], totalPEN: 0, totalUSD: 0, totalCount: 0 })),
    )
    expect(rows).toHaveLength(2)
    expect(rows[1]).toBe('TOTAL,,0,0,0')
  })

  it('antepone el BOM UTF-8', () => {
    expect(buildReportCsv(report()).startsWith(BOM)).toBe(true)
  })
})

describe('reportCsvFilename', () => {
  it('incluye el corte y el rango', () => {
    expect(reportCsvFilename(report())).toBe('almacen-reporte-por-unidad-2026-06-01_2026-06-30.csv')
  })

  it('cambia el slug con el corte', () => {
    expect(reportCsvFilename(report({ cut: 'BY_SUPPLIER' }))).toBe(
      'almacen-reporte-por-proveedor-2026-06-01_2026-06-30.csv',
    )
  })

  it('usa el rango que devolvió el backend, no uno local', () => {
    expect(reportCsvFilename(report({ dateFrom: '2026-01-01', dateTo: '2026-01-31' }))).toContain(
      '2026-01-01_2026-01-31',
    )
  })
})
