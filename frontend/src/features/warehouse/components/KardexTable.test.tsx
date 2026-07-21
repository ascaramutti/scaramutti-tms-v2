import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { KardexTable } from './KardexTable'
import { fakeKardexMovement } from '../../../test/mocks/handlers/warehouse'
import type { WarehouseKardexMovementResponse } from '../../../api'

function renderKardex(
  data: WarehouseKardexMovementResponse[],
  props: Partial<Parameters<typeof KardexTable>[0]> = {},
) {
  return render(
    <KardexTable
      data={data}
      page={0}
      size={10}
      total={data.length}
      totalPages={1}
      unitCode="UND"
      isLoading={false}
      isFetching={false}
      isError={false}
      onRetry={vi.fn()}
      onPageChange={vi.fn()}
      {...props}
    />,
  )
}

/** Sube del nodo de texto a su `<tr>` para acotar asserts a una fila. */
function rowOf(textNode: HTMLElement): HTMLElement {
  return textNode.closest('tr') as HTMLElement
}

describe('KardexTable', () => {
  it('suma el corte inicial', () => {
    renderKardex([
      fakeKardexMovement({
        movementType: 'APERTURA',
        quantity: 6,
        balance: 6,
        sourceId: null,
        reference: 'Corte inicial',
      }),
    ])
    const row = screen.getAllByRole('row')[1]
    expect(within(row).getByText('+6 UND')).toBeInTheDocument()
    // El distintivo y la referencia dicen lo mismo en el corte inicial.
    expect(within(row).getAllByText('Corte inicial')).toHaveLength(2)
  })

  it('suma las entradas', () => {
    renderKardex([fakeKardexMovement({ quantity: 10, balance: 16 })])
    const row = rowOf(screen.getByText(/Factura F001-123/))
    expect(within(row).getByText('Entrada')).toBeInTheDocument()
    expect(within(row).getByText('+10 UND')).toBeInTheDocument()
  })

  it('resta las salidas aunque la cantidad llegue positiva', () => {
    renderKardex([
      fakeKardexMovement({
        movementType: 'SALIDA',
        quantity: 4,
        balance: 12,
        reference: 'Retiro RET-0009',
      }),
    ])
    const row = rowOf(screen.getByText('Retiro RET-0009'))
    expect(within(row).getByText('Salida')).toBeInTheDocument()
    // El signo lo da el tipo de movimiento: la cantidad del backend es 4, no -4.
    expect(within(row).getByText('−4 UND')).toBeInTheDocument()
  })

  it('muestra el saldo del backend sin recalcularlo', () => {
    // Saldo deliberadamente incoherente con la cantidad: lo calcula el backend
    // sobre la historia completa, así que la tabla lo pinta tal cual.
    renderKardex([fakeKardexMovement({ quantity: 10, balance: 999, reference: 'Movimiento raro' })])
    const row = rowOf(screen.getByText('Movimiento raro'))
    expect(within(row).getByText('999 UND')).toBeInTheDocument()
  })

  it('respeta el orden recibido del backend', () => {
    renderKardex([
      fakeKardexMovement({ reference: 'Más reciente', movedAt: '2026-07-10T15:00:00Z' }),
      fakeKardexMovement({ reference: 'Más antiguo', movedAt: '2026-06-01T13:00:00Z' }),
    ])
    const references = screen.getAllByRole('row').slice(1).map((row) => row.textContent)
    expect(references[0]).toContain('Más reciente')
    expect(references[1]).toContain('Más antiguo')
  })

  it('muestra quién registró el movimiento', () => {
    renderKardex([fakeKardexMovement()])
    expect(screen.getByText('Admin TMS')).toBeInTheDocument()
  })

  it('formatea la fecha con hora en horario de Lima', () => {
    renderKardex([fakeKardexMovement({ movedAt: '2026-07-05T14:00:00Z' })])
    // 14:00 UTC son las 09:00 en Lima (UTC-5).
    expect(screen.getByText('05/07/2026, 09:00')).toBeInTheDocument()
  })

  it('acompaña las cantidades con la unidad del producto', () => {
    renderKardex([fakeKardexMovement({ quantity: 2, balance: 8 })], { unitCode: 'GAL' })
    expect(screen.getByText('+2 GAL')).toBeInTheDocument()
    expect(screen.getByText('8 GAL')).toBeInTheDocument()
  })

  it('muestra el estado vacío cuando no hay movimientos', () => {
    renderKardex([], { total: 0, totalPages: 0 })
    expect(screen.getByText(/todavía no hay movimientos/i)).toBeInTheDocument()
  })

  it('muestra el error del backend con la opción de reintentar', () => {
    renderKardex([], {
      total: 0,
      totalPages: 0,
      isError: true,
      errorMessage: 'No se pudo cargar el kardex.',
    })
    expect(screen.getByRole('alert')).toHaveTextContent('No se pudo cargar el kardex.')
  })

  it('expone los encabezados de columna', () => {
    renderKardex([fakeKardexMovement()])
    for (const header of ['Fecha', 'Movimiento', 'Referencia', 'Registró', 'Cantidad', 'Saldo']) {
      expect(screen.getByRole('columnheader', { name: header })).toBeInTheDocument()
    }
  })

  it('renderiza un corte inicial sin origen sin romperse', () => {
    renderKardex([
      fakeKardexMovement({ movementType: 'APERTURA', sourceId: null, reference: 'Corte inicial' }),
    ])
    expect(screen.getAllByRole('row')).toHaveLength(2)
  })
})
