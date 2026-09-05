import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { EntriesListPage } from './EntriesListPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import type { ProductsCaptureSink } from '../../../test/mocks/handlers/warehouse'
import {
  fakeInvoiceSummary,
  warehouseInvoicesCapture,
  warehouseInvoicesEmpty,
  warehouseInvoicesError,
  warehouseInvoicesOkThenErrorOnNextPage,
  warehouseInvoicesPage,
  warehouseInvoicesPagedByParam,
  warehouseInvoicesSlow,
} from '../../../test/mocks/handlers/warehouse'
import { buttonClasses } from '../../../shared/ui/buttonClasses'
import { cn } from '../../../shared/utils/cn'

function renderEntradas() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  // La página vive dentro del layout autenticado: sesión admin sembrada en cache.
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, fakeUser)
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/cotizaciones/almacen/entradas']}>
          <Routes>
            <Route path="/cotizaciones/almacen/entradas" element={<EntriesListPage />} />
            <Route
              path="/cotizaciones/almacen/entradas/nueva"
              element={<div>NUEVA ENTRADA STUB</div>}
            />
            <Route
              path="/cotizaciones/almacen/entradas/:id"
              element={<div>DETALLE ENTRADA STUB</div>}
            />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

/** Sube del nodo de texto a su `<tr>` para acotar asserts a una fila. */
function rowOf(textNode: HTMLElement): HTMLElement {
  return textNode.closest('tr') as HTMLElement
}

describe('EntriesListPage', () => {
  // ----- Render -----
  it('muestra el spinner de la tabla durante la carga inicial', async () => {
    server.use(warehouseInvoicesSlow([fakeInvoiceSummary()], 40))
    renderEntradas()
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0)
    // Esperamos la resolución para no dejar el fetch colgado tras el unmount.
    expect(await screen.findByText('F001-00123')).toBeInTheDocument()
  })

  it('renderiza las filas de la página (happy path)', async () => {
    renderEntradas()
    expect(await screen.findByText('F001-00123')).toBeInTheDocument()
    expect(screen.getByText('F001-00124')).toBeInTheDocument()
    expect(screen.getByText('F002-00001')).toBeInTheDocument()
  })

  it('clickear una fila navega al detalle de la entrada', async () => {
    server.use(warehouseInvoicesPage([fakeInvoiceSummary({ id: 7, invoiceNumber: 'F001-00700' })]))
    renderEntradas()
    const user = userEvent.setup()
    await user.click(rowOf(await screen.findByText('F001-00700')))
    expect(screen.getByText('DETALLE ENTRADA STUB')).toBeInTheDocument()
  })

  it('mapea los campos de la factura en su fila', async () => {
    server.use(
      warehouseInvoicesPage([
        fakeInvoiceSummary({
          invoiceNumber: 'F001-00500',
          supplier: { id: 4, name: 'REPUESTOS DIÉSEL S.A.C.' },
          invoiceDate: '2026-07-02',
          guideNumber: 'T001-0004567',
          currencyCode: 'PEN',
          itemsCount: 3,
          total: 1234.5,
        }),
      ]),
    )
    renderEntradas()
    const row = rowOf(await screen.findByText('F001-00500'))
    expect(within(row).getByText('REPUESTOS DIÉSEL S.A.C.')).toBeInTheDocument()
    expect(within(row).getByText('Guía T001-0004567')).toBeInTheDocument()
    expect(within(row).getByText('02/07/2026')).toBeInTheDocument()
    expect(within(row).getByText('3')).toBeInTheDocument()
    expect(within(row).getByText(/1,234\.50/)).toBeInTheDocument()
    expect(within(row).getByText(/registró admin tms/i)).toBeInTheDocument()
  })

  it('no muestra la línea de guía cuando la factura no la tiene', async () => {
    server.use(
      warehouseInvoicesPage([fakeInvoiceSummary({ invoiceNumber: 'F009-1', guideNumber: null })]),
    )
    renderEntradas()
    const row = rowOf(await screen.findByText('F009-1'))
    expect(within(row).queryByText(/guía/i)).not.toBeInTheDocument()
  })

  it('muestra el badge de anulada con su motivo', async () => {
    server.use(
      warehouseInvoicesPage([
        fakeInvoiceSummary({
          invoiceNumber: 'F003-9',
          status: 'CANCELLED',
          cancelReason: 'Factura cargada dos veces',
        }),
      ]),
    )
    renderEntradas()
    const row = rowOf(await screen.findByText('F003-9'))
    expect(within(row).getByText('Anulada')).toBeInTheDocument()
    expect(within(row).getByText('Factura cargada dos veces')).toBeInTheDocument()
  })

  it('marca la factura vigente como activa, con texto y no solo con color', async () => {
    server.use(warehouseInvoicesPage([fakeInvoiceSummary({ invoiceNumber: 'F004-1' })]))
    renderEntradas()
    const row = rowOf(await screen.findByText('F004-1'))
    expect(within(row).getByText('Activa')).toBeInTheDocument()
    expect(within(row).queryByText('Anulada')).not.toBeInTheDocument()
  })

  it('muestra el estado vacío de base cuando no hay entradas ni filtros', async () => {
    server.use(warehouseInvoicesEmpty())
    renderEntradas()
    expect(await screen.findByText(/aún no hay entradas/i)).toBeInTheDocument()
  })

  it('muestra el estado vacío de filtros cuando hay filtros activos y cero resultados', async () => {
    server.use(warehouseInvoicesEmpty())
    const user = userEvent.setup()
    renderEntradas()
    await screen.findByText(/aún no hay entradas/i)
    await user.selectOptions(screen.getByLabelText(/estado/i), 'CANCELLED')
    expect(await screen.findByText(/no se encontraron entradas/i)).toBeInTheDocument()
  })

  it('muestra el error del backend y permite reintentar', async () => {
    server.use(warehouseInvoicesError(500, { detail: 'El servidor falló al listar.' }))
    const user = userEvent.setup()
    renderEntradas()
    expect(await screen.findByText('El servidor falló al listar.')).toBeInTheDocument()
    server.use(warehouseInvoicesPage([fakeInvoiceSummary({ invoiceNumber: 'F007-7' })]))
    await user.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByText('F007-7')).toBeInTheDocument()
  })

  // ----- Filtros -----
  it('no envía q con menos de 3 caracteres y muestra el mensaje guía', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseInvoicesCapture(sink))
    const user = userEvent.setup()
    renderEntradas()
    await screen.findByText('F001-00123')
    await user.type(screen.getByLabelText(/buscar/i), 'F0')
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    expect((sink.calls ?? []).every((params) => params.get('q') === null)).toBe(true)
  })

  it('envía q con 3 o más caracteres', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseInvoicesCapture(sink))
    const user = userEvent.setup()
    renderEntradas()
    await screen.findByText('F001-00123')
    await user.type(screen.getByLabelText(/buscar/i), 'F001')
    await waitFor(() => expect(sink.params?.get('q')).toBe('F001'))
  })

  it('deja de enviar q al borrar la búsqueda', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseInvoicesCapture(sink))
    const user = userEvent.setup()
    renderEntradas()
    await screen.findByText('F001-00123')
    const search = screen.getByLabelText(/buscar/i)
    await user.type(search, 'F001')
    await waitFor(() => expect(sink.params?.get('q')).toBe('F001'))
    await user.clear(search)
    await waitFor(() => expect(sink.params?.get('q')).toBeNull())
  })

  it('filtra por estado anulada', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseInvoicesCapture(sink))
    const user = userEvent.setup()
    renderEntradas()
    await screen.findByText('F001-00123')
    await user.selectOptions(screen.getByLabelText(/estado/i), 'CANCELLED')
    await waitFor(() => expect(sink.params?.get('status')).toBe('CANCELLED'))
  })

  it('el estado "Todos" omite el parámetro status', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseInvoicesCapture(sink))
    const user = userEvent.setup()
    renderEntradas()
    await screen.findByText('F001-00123')
    await user.selectOptions(screen.getByLabelText(/estado/i), 'CANCELLED')
    await waitFor(() => expect(sink.params?.get('status')).toBe('CANCELLED'))
    await user.selectOptions(screen.getByLabelText(/estado/i), '')
    await waitFor(() => expect(sink.params?.get('status')).toBeNull())
  })

  it('filtra por rango de fechas', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseInvoicesCapture(sink))
    const user = userEvent.setup()
    renderEntradas()
    await screen.findByText('F001-00123')
    await user.type(screen.getByLabelText(/desde/i), '2026-07-01')
    await user.type(screen.getByLabelText(/hasta/i), '2026-07-31')
    await waitFor(() => {
      expect(sink.params?.get('dateFrom')).toBe('2026-07-01')
      expect(sink.params?.get('dateTo')).toBe('2026-07-31')
    })
  })

  it('avisa del rango invertido y no consulta con él', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseInvoicesCapture(sink))
    const user = userEvent.setup()
    renderEntradas()
    await screen.findByText('F001-00123')
    // El "hasta" primero: con un solo extremo el rango todavía es válido, así que
    // recién al escribir el "desde" posterior queda invertido.
    await user.type(screen.getByLabelText(/hasta/i), '2026-07-01')
    await waitFor(() => expect(sink.params?.get('dateTo')).toBe('2026-07-01'))
    await user.type(screen.getByLabelText(/desde/i), '2026-07-31')
    expect(await screen.findByRole('alert')).toHaveTextContent(/no puede ser posterior/i)
    // El rango invertido no viaja: se consulta sin fechas en vez de traer un vacío
    // indistinguible de "no hay entradas".
    await waitFor(() => expect(sink.params?.get('dateTo')).toBeNull())
    expect((sink.calls ?? []).every((params) => params.get('dateFrom') === null)).toBe(true)
  })

  it('reinicia a la primera página al cambiar un filtro', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseInvoicesPagedByParam(25, 10))
    const user = userEvent.setup()
    renderEntradas()
    await screen.findByText('F000-1')
    await user.click(screen.getByLabelText('Página siguiente'))
    await screen.findByText('F001-1')
    server.use(warehouseInvoicesCapture(sink))
    await user.selectOptions(screen.getByLabelText(/estado/i), 'ACTIVE')
    await waitFor(() => {
      expect(sink.params?.get('page')).toBe('0')
      expect(sink.params?.get('status')).toBe('ACTIVE')
    })
  })

  // ----- Paginación -----
  it('deshabilita "Página anterior" en la primera página', async () => {
    server.use(warehouseInvoicesPagedByParam(25, 10))
    renderEntradas()
    await screen.findByText('F000-1')
    expect(screen.getByLabelText('Página anterior')).toBeDisabled()
  })

  it('pide la página siguiente al avanzar', async () => {
    server.use(warehouseInvoicesPagedByParam(25, 10))
    const user = userEvent.setup()
    renderEntradas()
    await screen.findByText('F000-1')
    await user.click(screen.getByLabelText('Página siguiente'))
    expect(await screen.findByText('F001-1')).toBeInTheDocument()
  })

  it('mantiene la tabla previa y avisa cuando falla el refetch al paginar', async () => {
    server.use(
      warehouseInvoicesOkThenErrorOnNextPage(
        [fakeInvoiceSummary({ invoiceNumber: 'F010-1' })],
        { totalElements: 25, size: 10 },
      ),
    )
    const user = userEvent.setup()
    renderEntradas()
    await screen.findByText('F010-1')
    await user.click(screen.getByLabelText('Página siguiente'))
    expect(await screen.findByText('Fallo al paginar')).toBeInTheDocument()
    expect(screen.getByText('F010-1')).toBeInTheDocument()
  })

  // ----- Navegación -----
  it('el enlace de registrar es la acción principal y lleva al formulario de nueva entrada', async () => {
    const user = userEvent.setup()
    renderEntradas()
    await screen.findByText('F001-00123')
    // Es un enlace con pinta de botón: tiene que NAVEGAR (por eso el click de abajo) y
    // tiene que verse como la acción principal del listado. Sin esta línea, pintarlo de
    // secundario dejaba todos los casos del archivo en verde: medido.
    expect(screen.getByRole('link', { name: /registrar entrada/i }).className).toBe(
      cn(buttonClasses({ variant: 'primary' }), 'gap-1.5'),
    )
    await user.click(screen.getByRole('link', { name: /registrar entrada/i }))
    expect(await screen.findByText('NUEVA ENTRADA STUB')).toBeInTheDocument()
  })

  // ----- A11y -----
  it('expone los encabezados de columna de la tabla', async () => {
    renderEntradas()
    await screen.findByText('F001-00123')
    for (const header of ['N° factura', 'Proveedor', 'Fecha', 'Ítems', 'Total', 'Estado']) {
      expect(screen.getByRole('columnheader', { name: header })).toBeInTheDocument()
    }
  })

  it('presenta la pantalla con un único h1', async () => {
    renderEntradas()
    await screen.findByText('F001-00123')
    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings).toHaveLength(1)
    expect(headings[0]).toHaveTextContent('Entradas')
  })

  it('rotula todos los controles de filtro', async () => {
    renderEntradas()
    await screen.findByText('F001-00123')
    expect(screen.getByLabelText(/buscar/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/estado/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/desde/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/hasta/i)).toBeInTheDocument()
  })

  it('no tiene violaciones de accesibilidad', async () => {
    const { container } = renderEntradas()
    await screen.findByText('F001-00123')
    expect(await axe(container)).toHaveNoViolations()
  })
})
