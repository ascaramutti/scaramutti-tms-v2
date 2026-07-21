import { describe, expect, it } from 'vitest'
import { buildQuery, SEARCH_MIN_LENGTH } from './useWarehouseProductsList'
import { EMPTY_STOCK_FILTERS, type StockFilters } from '../schemas/stock-filters.schema'

function filters(overrides: Partial<StockFilters> = {}): StockFilters {
  return { ...EMPTY_STOCK_FILTERS, ...overrides }
}

describe('buildQuery (useWarehouseProductsList)', () => {
  it('omite q con menos de 3 caracteres', () => {
    const query = buildQuery({ page: 0, size: 10, filters: filters({ q: 'ac' }) })
    expect(query.q).toBeUndefined()
  })

  it('incluye q con 3 o más caracteres', () => {
    const query = buildQuery({ page: 0, size: 10, filters: filters({ q: 'filtro' }) })
    expect(query.q).toBe('filtro')
  })

  it('recorta espacios de q antes de medir la longitud', () => {
    expect(buildQuery({ page: 0, size: 10, filters: filters({ q: '  ac  ' }) }).q).toBeUndefined()
    expect(buildQuery({ page: 0, size: 10, filters: filters({ q: '  aceite ' }) }).q).toBe('aceite')
  })

  it('omite categoryId cuando no hay filtro de categoría', () => {
    const query = buildQuery({ page: 0, size: 10, filters: filters() })
    expect(query.categoryId).toBeUndefined()
    expect(buildQuery({ page: 0, size: 10, filters: filters({ categoryId: 7 }) }).categoryId).toBe(7)
  })

  it('incluye lowOnly solo cuando es true (para no filtrar el contrato pide omitirlo)', () => {
    expect(buildQuery({ page: 0, size: 10, filters: filters({ lowOnly: false }) }).lowOnly)
      .toBeUndefined()
    expect(buildQuery({ page: 0, size: 10, filters: filters({ lowOnly: true }) }).lowOnly).toBe(true)
  })

  it('fija isActive en true (Existencias muestra el inventario vigente)', () => {
    expect(buildQuery({ page: 0, size: 10, filters: filters() }).isActive).toBe(true)
  })

  it('siempre incluye page y size', () => {
    const query = buildQuery({ page: 2, size: 10, filters: filters() })
    expect(query.page).toBe(2)
    expect(query.size).toBe(10)
  })

  it('expone el mínimo de búsqueda del contrato', () => {
    expect(SEARCH_MIN_LENGTH).toBe(3)
  })
})
