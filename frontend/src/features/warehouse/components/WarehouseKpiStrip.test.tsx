import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { WarehouseKpiStrip } from './WarehouseKpiStrip'
import { fakeWarehouseStats } from '../../../test/mocks/handlers/warehouse'

function renderStrip(props: Partial<Parameters<typeof WarehouseKpiStrip>[0]> = {}) {
  const onToggleLowOnly = props.onToggleLowOnly ?? vi.fn()
  render(
    <WarehouseKpiStrip
      data={fakeWarehouseStats()}
      isLoading={false}
      isError={false}
      onRetry={vi.fn()}
      lowOnly={false}
      {...props}
      onToggleLowOnly={onToggleLowOnly}
    />,
  )
  return { onToggleLowOnly }
}

describe('WarehouseKpiStrip', () => {
  it('renderiza los cuatro indicadores con sus rótulos', () => {
    renderStrip({
      data: fakeWarehouseStats({
        activeProducts: 120,
        lowStockCount: 7,
        entriesThisMonth: 12,
        withdrawalsThisMonth: 31,
      }),
    })
    expect(screen.getByText('Productos activos')).toBeInTheDocument()
    expect(screen.getByText('120')).toBeInTheDocument()
    expect(screen.getByText('Con stock bajo')).toBeInTheDocument()
    expect(screen.getByText('7')).toBeInTheDocument()
    expect(screen.getByText('Entradas del mes')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.getByText('Retiros del mes')).toBeInTheDocument()
    expect(screen.getByText('31')).toBeInTheDocument()
  })

  it('muestra el cero como dato, no como vacío', () => {
    renderStrip({ data: fakeWarehouseStats({ lowStockCount: 0 }) })
    expect(screen.getByText('0')).toBeInTheDocument()
    expect(screen.queryByText('—')).not.toBeInTheDocument()
  })

  it('muestra el estado de carga mientras llegan los indicadores', () => {
    renderStrip({ data: undefined, isLoading: true })
    expect(screen.getAllByRole('status')).toHaveLength(4)
  })

  it('muestra un aviso degradado ante un error y permite reintentar', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    renderStrip({
      data: undefined,
      isError: true,
      errorMessage: 'No se pudieron cargar los indicadores.',
      onRetry,
    })
    expect(screen.getByRole('alert')).toHaveTextContent('No se pudieron cargar los indicadores.')
    // Los tiles siguen en pantalla con un guion: el strip se degrada, no desaparece.
    expect(screen.getAllByText('—')).toHaveLength(4)
    await user.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('el tile de stock bajo togglea el filtro y refleja su estado', async () => {
    const user = userEvent.setup()
    const { onToggleLowOnly } = renderStrip({ lowOnly: true })
    const tile = screen.getByRole('button', { name: /solo stock bajo/i })
    expect(tile).toHaveAttribute('aria-pressed', 'true')
    await user.click(tile)
    expect(onToggleLowOnly).toHaveBeenCalledOnce()
  })

  it('los indicadores del mes no son interactivos todavía', () => {
    renderStrip()
    // El único botón del strip es el corte de stock bajo: entradas y retiros
    // navegarían a pantallas que aún no existen.
    expect(screen.getAllByRole('button')).toHaveLength(1)
  })
})
