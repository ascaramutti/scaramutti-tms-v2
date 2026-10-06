import { describe, expect, it } from 'vitest'
import {
  WORKER_STATUS_FILTER_DEFAULT,
  WORKER_STATUS_FILTER_OPTIONS,
  isActiveParam,
} from './workerStatusFilter'

describe('workerStatusFilter', () => {
  it('se abre en activos', () => {
    expect(WORKER_STATUS_FILTER_DEFAULT).toBe('active')
  })

  it('ofrece los tres filtros en este orden', () => {
    expect(WORKER_STATUS_FILTER_OPTIONS.map((option) => option.label)).toEqual([
      'Activos',
      'Inactivos',
      'Todos',
    ])
  })

  it('cada filtro manda su isActive, y Todos no manda ninguno', () => {
    expect(isActiveParam('active')).toBe(true)
    expect(isActiveParam('inactive')).toBe(false)
    expect(isActiveParam('all')).toBeUndefined()
  })
})
