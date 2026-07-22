import { describe, expect, it } from 'vitest'
import { buildQuery, ENTRY_SEARCH_MIN_LENGTH } from './useWarehousePurchaseInvoicesList'
import { EMPTY_ENTRY_FILTERS, type EntryFilters } from '../schemas/entry-filters.schema'

function filters(overrides: Partial<EntryFilters> = {}): EntryFilters {
  return { ...EMPTY_ENTRY_FILTERS, ...overrides }
}

describe('buildQuery (useWarehousePurchaseInvoicesList)', () => {
  it('omite q con menos de 3 caracteres', () => {
    expect(buildQuery({ page: 0, size: 10, filters: filters({ q: 'F0' }) }).q).toBeUndefined()
  })

  it('incluye q con 3 o más caracteres', () => {
    expect(buildQuery({ page: 0, size: 10, filters: filters({ q: 'F001' }) }).q).toBe('F001')
  })

  it('recorta espacios de q antes de medir la longitud', () => {
    expect(buildQuery({ page: 0, size: 10, filters: filters({ q: '  F0 ' }) }).q).toBeUndefined()
    expect(buildQuery({ page: 0, size: 10, filters: filters({ q: ' F001 ' }) }).q).toBe('F001')
  })

  it('omite status cuando el filtro es "todos"', () => {
    expect(buildQuery({ page: 0, size: 10, filters: filters() }).status).toBeUndefined()
  })

  it('incluye status cuando se filtra por estado', () => {
    expect(buildQuery({ page: 0, size: 10, filters: filters({ status: 'CANCELLED' }) }).status).toBe(
      'CANCELLED',
    )
  })

  it('omite las fechas cuando el rango está vacío', () => {
    const query = buildQuery({ page: 0, size: 10, filters: filters() })
    expect(query.dateFrom).toBeUndefined()
    expect(query.dateTo).toBeUndefined()
  })

  it('incluye el rango de fechas cuando está completo', () => {
    const query = buildQuery({
      page: 0,
      size: 10,
      filters: filters({ dateFrom: '2026-07-01', dateTo: '2026-07-31' }),
    })
    expect(query.dateFrom).toBe('2026-07-01')
    expect(query.dateTo).toBe('2026-07-31')
  })

  it('siempre incluye page y size', () => {
    const query = buildQuery({ page: 2, size: 10, filters: filters() })
    expect(query.page).toBe(2)
    expect(query.size).toBe(10)
  })

  it('expone el mínimo de búsqueda del contrato', () => {
    expect(ENTRY_SEARCH_MIN_LENGTH).toBe(3)
  })
})
