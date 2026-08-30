import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { WithdrawalsListPage } from './WithdrawalsListPage'
import { FLEET_UNITS_LOAD_ERROR } from '../components/WithdrawalsFilterBar'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import type { ProductsCaptureSink } from '../../../test/mocks/handlers/warehouse'
import {
  fakeProduct,
  fakeWithdrawal,
  warehouseProductsCapture,
  warehouseProductsPage,
  warehouseWithdrawalsCapture,
  warehouseWithdrawalsEmpty,
  warehouseWithdrawalsError,
  warehouseWithdrawalsOkThenErrorOnNextPage,
  warehouseWithdrawalsPage,
  warehouseWithdrawalsPagedByParam,
  warehouseWithdrawalsSlow,
} from '../../../test/mocks/handlers/warehouse'
import {
  fakeFleetUnit,
  fakeWorker,
  fleetUnitsByKind,
  fleetUnitsError,
  fleetUnitsList,
  workersSearchCapture,
} from '../../../test/mocks/handlers/shared-catalogs'

function renderRetiros() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, fakeUser)
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/cotizaciones/almacen/retiros']}>
          <Routes>
            <Route path="/cotizaciones/almacen/retiros" element={<WithdrawalsListPage />} />
            <Route
              path="/cotizaciones/almacen/retiros/nuevo"
              element={<div>NUEVO RETIRO STUB</div>}
            />
            <Route path="/cotizaciones/almacen/retiros/:id" element={<div>DETALLE STUB</div>} />
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

describe('WithdrawalsListPage', () => {
  // ----- Render -----
  it('muestra el spinner de la tabla durante la carga inicial', async () => {
    server.use(warehouseWithdrawalsSlow([fakeWithdrawal()], 40))
    renderRetiros()
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0)
    expect(await screen.findByText('Filtro de aceite XYZ')).toBeInTheDocument()
  })

  it('renderiza las filas de la página (happy path)', async () => {
    renderRetiros()
    // Los tres retiros del default se distinguen por su receptor.
    expect(await screen.findByText('Aceite 15W40')).toBeInTheDocument()
    expect(screen.getByText('María López Díaz')).toBeInTheDocument()
    expect(screen.getAllByText('Juan Pérez Gómez').length).toBeGreaterThan(0)
  })

  it('las filas ofrecen la navegación al detalle como control accesible', async () => {
    server.use(
      warehouseWithdrawalsPage([
        fakeWithdrawal({ id: 7, receivedBy: fakeWorker({ fullName: 'Pedro Salas' }) }),
      ]),
    )
    renderRetiros()
    const row = rowOf(await screen.findByText('Pedro Salas'))
    // La fila entera es el control (rol de botón + foco), no un link en una celda.
    expect(row).toHaveAttribute('role', 'button')
    expect(row).toHaveAttribute('tabindex', '0')
  })

  it('mapea los campos del retiro en su fila', async () => {
    server.use(
      warehouseWithdrawalsPage([
        fakeWithdrawal({
          product: { id: 1, code: 'PRO-0001', name: 'Filtro de aceite XYZ', unitCode: 'UND' },
          quantity: 4,
          withdrawnAt: '2026-07-10T09:00:00Z',
          receivedBy: fakeWorker({ fullName: 'Juan Pérez Gómez' }),
          fleetUnit: { kind: 'TRACTOR', id: 5, plate: 'ABC-123' },
        }),
      ]),
    )
    renderRetiros()
    const row = rowOf(await screen.findByText('Filtro de aceite XYZ'))
    expect(within(row).getByText('PRO-0001')).toBeInTheDocument()
    expect(within(row).getByText(/^4 UND$/)).toBeInTheDocument()
    expect(within(row).getByText('Juan Pérez Gómez')).toBeInTheDocument()
    expect(within(row).getByText('Tracto ABC-123')).toBeInTheDocument()
    expect(within(row).getByText('10/07/2026')).toBeInTheDocument()
    expect(within(row).getByText(/registró admin tms/i)).toBeInTheDocument()
  })

  it('muestra la etiqueta de la unidad según su tipo', async () => {
    server.use(
      warehouseWithdrawalsPage([
        fakeWithdrawal({
          id: 1,
          receivedBy: fakeWorker({ fullName: 'A A' }),
          fleetUnit: { kind: 'TRAILER', id: 9, plate: 'XY-9876' },
        }),
        fakeWithdrawal({
          id: 2,
          receivedBy: fakeWorker({ fullName: 'B B' }),
          fleetUnit: { kind: 'ESCORT', id: 3, plate: 'ES-100' },
        }),
      ]),
    )
    renderRetiros()
    expect(await screen.findByText('Carreta XY-9876')).toBeInTheDocument()
    expect(screen.getByText('Escolta ES-100')).toBeInTheDocument()
  })

  it('muestra "Sin unidad" cuando el retiro no tiene unidad de flota', async () => {
    server.use(
      warehouseWithdrawalsPage([
        fakeWithdrawal({ receivedBy: fakeWorker({ fullName: 'Sin Flota' }), fleetUnit: undefined }),
      ]),
    )
    renderRetiros()
    const row = rowOf(await screen.findByText('Sin Flota'))
    expect(within(row).getByText('Sin unidad')).toBeInTheDocument()
  })

  it('muestra el badge de anulado con su motivo', async () => {
    server.use(
      warehouseWithdrawalsPage([
        fakeWithdrawal({
          receivedBy: fakeWorker({ fullName: 'Anulado Test' }),
          status: 'CANCELLED',
          cancelReason: 'Retiro duplicado',
        }),
      ]),
    )
    renderRetiros()
    const row = rowOf(await screen.findByText('Anulado Test'))
    expect(within(row).getByText('Anulado')).toBeInTheDocument()
    expect(within(row).getByText('Retiro duplicado')).toBeInTheDocument()
  })

  it('marca el retiro vigente como activo, con texto y no solo con color', async () => {
    server.use(
      warehouseWithdrawalsPage([
        fakeWithdrawal({ receivedBy: fakeWorker({ fullName: 'Activo Test' }), status: 'ACTIVE' }),
      ]),
    )
    renderRetiros()
    const row = rowOf(await screen.findByText('Activo Test'))
    expect(within(row).getByText('Activo')).toBeInTheDocument()
    expect(within(row).queryByText('Anulado')).not.toBeInTheDocument()
  })

  it('muestra el estado vacío de base cuando no hay retiros ni filtros', async () => {
    server.use(warehouseWithdrawalsEmpty())
    renderRetiros()
    expect(await screen.findByText(/aún no hay retiros/i)).toBeInTheDocument()
  })

  it('muestra el estado vacío de filtros cuando hay filtros activos y cero resultados', async () => {
    server.use(warehouseWithdrawalsEmpty())
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText(/aún no hay retiros/i)
    await user.selectOptions(screen.getByLabelText(/estado/i), 'CANCELLED')
    expect(await screen.findByText(/no se encontraron retiros/i)).toBeInTheDocument()
  })

  it('muestra el error del backend y permite reintentar', async () => {
    server.use(warehouseWithdrawalsError(500, { detail: 'El servidor falló al listar.' }))
    const user = userEvent.setup()
    renderRetiros()
    expect(await screen.findByText('El servidor falló al listar.')).toBeInTheDocument()
    server.use(
      warehouseWithdrawalsPage([fakeWithdrawal({ receivedBy: fakeWorker({ fullName: 'Reintento OK' }) })]),
    )
    await user.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByText('Reintento OK')).toBeInTheDocument()
  })

  // ----- Filtros -----
  it('el filtro de producto no busca con menos de 3 caracteres y muestra la guía', async () => {
    const prodSink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(prodSink, [fakeProduct()]))
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText('Aceite 15W40')
    await user.type(screen.getByLabelText('Producto'), 'fi')
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    expect(prodSink.calls ?? []).toHaveLength(0)
  })

  it('el filtro de producto busca con 3+ y filtra por productId', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(
      warehouseProductsPage([fakeProduct({ id: 1 })]),
      warehouseWithdrawalsCapture(sink, [
        fakeWithdrawal({ receivedBy: fakeWorker({ fullName: 'Base Row' }) }),
      ]),
    )
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText('Base Row')
    await user.type(screen.getByLabelText('Producto'), 'fil')
    const listbox = await screen.findByRole('listbox')
    await user.click(await within(listbox).findByText('Filtro de aceite XYZ'))
    await waitFor(() => expect(sink.params?.get('productId')).toBe('1'))
  })

  it('el filtro de trabajador no busca con menos de 3 caracteres', async () => {
    const workerSink: ProductsCaptureSink = {}
    server.use(workersSearchCapture(workerSink))
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText('Aceite 15W40')
    await user.type(screen.getByLabelText('Recibido por'), 'ju')
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    expect(workerSink.calls ?? []).toHaveLength(0)
  })

  it('el filtro de trabajador busca multi-palabra y filtra por receivedByWorkerId', async () => {
    const workerSink: ProductsCaptureSink = {}
    const sink: ProductsCaptureSink = {}
    server.use(
      workersSearchCapture(workerSink, [fakeWorker({ id: 8, fullName: 'Juan Pérez Gómez' })]),
      warehouseWithdrawalsCapture(sink, [
        fakeWithdrawal({ receivedBy: fakeWorker({ fullName: 'Base Row' }) }),
      ]),
    )
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText('Base Row')
    await user.type(screen.getByLabelText('Recibido por'), 'juan perez')
    const listbox = await screen.findByRole('listbox')
    await user.click(await within(listbox).findByText('Juan Pérez Gómez'))
    await waitFor(() => {
      expect(workerSink.params?.get('q')).toBe('juan perez')
      expect(sink.params?.get('receivedByWorkerId')).toBe('8')
    })
  })

  it('el filtro de unidad de flota manda el id en el campo del subtipo', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(
      warehouseWithdrawalsCapture(sink, [
        fakeWithdrawal({ receivedBy: fakeWorker({ fullName: 'Base Row' }), fleetUnit: undefined }),
      ]),
    )
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText('Base Row')
    // La flota se carga entera; al enfocar el combobox se abre la lista.
    await user.click(screen.getByLabelText('Unidad de flota'))
    const listbox = await screen.findByRole('listbox')
    await user.click(await within(listbox).findByText('Tracto ABC-123'))
    await waitFor(() => expect(sink.params?.get('tractorId')).toBe('5'))
  })

  it('el filtro de unidad pide la flota entera: solo las vigentes y sin acotar el subtipo', async () => {
    const fleetSink: ProductsCaptureSink = {}
    server.use(
      fleetUnitsByKind(
        [
          fakeFleetUnit({ kind: 'TRACTOR', id: 5, plate: 'ABC-123' }),
          fakeFleetUnit({ kind: 'TRAILER', id: 9, plate: 'XY-9876' }),
          fakeFleetUnit({ kind: 'ESCORT', id: 3, plate: 'ES-100' }),
        ],
        fleetSink,
      ),
    )
    const user = userEvent.setup()
    renderRetiros()
    // El listado por defecto trae varias filas del mismo producto: se espera a que
    // haya alguna, no a que haya exactamente una.
    await screen.findAllByText('Filtro de aceite XYZ')
    await user.click(screen.getByLabelText('Unidad de flota'))

    await waitFor(() => expect(fleetSink.calls).toHaveLength(1))
    expect(fleetSink.params?.get('isActive')).toBe('true')
    // El filtro ofrece las tres clases de unidad, así que NO manda subtipo. Se mide
    // sobre la consulta y además sobre lo que llega: un subtipo de más dejaría al
    // filtro sin dos de sus tres opciones.
    expect(fleetSink.params?.has('kind')).toBe(false)
    const listbox = await screen.findByRole('listbox')
    expect(within(listbox).getByText('Tracto ABC-123')).toBeInTheDocument()
    expect(within(listbox).getByText('Carreta XY-9876')).toBeInTheDocument()
    expect(within(listbox).getByText('Escolta ES-100')).toBeInTheDocument()
  })

  it('avisa cuando la carga de unidades falla, sin bloquear el resto de los filtros', async () => {
    server.use(fleetUnitsError(500))
    const user = userEvent.setup()
    renderRetiros()
    await screen.findAllByText('Filtro de aceite XYZ')
    await user.click(screen.getByLabelText('Unidad de flota'))

    // Se compara contra la MISMA constante que la pantalla le pasa al campo, y no
    // contra la frase repetida acá, para no dejarla clavada en dos lugares: el
    // texto de esta pantalla está mal (habla de registrar en una pantalla que
    // filtra) y clavarlo encarecería corregirlo. Lo que el caso mide es el tránsito
    // de la constante hasta el aviso, así que muere si la prop viaja cambiada o
    // cruzada con el placeholder. Que la prop FALTE ya no lo caza este test sino el
    // compilador, porque es obligatoria.
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe(FLEET_UNITS_LOAD_ERROR)
    expect(screen.getByLabelText('Recibido por')).toBeEnabled()
  })

  it('el filtro de unidad ofrece su propio texto de ayuda', async () => {
    const user = userEvent.setup()
    renderRetiros()
    await screen.findAllByText('Filtro de aceite XYZ')
    await user.click(screen.getByLabelText('Unidad de flota'))

    expect(screen.getByLabelText('Unidad de flota')).toHaveAttribute(
      'placeholder',
      'Tracto, carreta o escolta (opcional)…',
    )
  })

  it('el filtro de unidad resuelve por (kind, id): con ids colisionados filtra el campo correcto', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(
      // Un tracto y una carreta con el mismo id (5): elegir la carreta debe filtrar
      // por trailerId, no por tractorId.
      fleetUnitsList([
        fakeFleetUnit({ kind: 'TRACTOR', id: 5, plate: 'ABC-123' }),
        fakeFleetUnit({ kind: 'TRAILER', id: 5, plate: 'XY-9876' }),
      ]),
      warehouseWithdrawalsCapture(sink, [
        fakeWithdrawal({ receivedBy: fakeWorker({ fullName: 'Base Row' }), fleetUnit: undefined }),
      ]),
    )
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText('Base Row')
    await user.click(screen.getByLabelText('Unidad de flota'))
    const listbox = await screen.findByRole('listbox')
    await user.click(await within(listbox).findByText('Carreta XY-9876'))
    await waitFor(() => expect(sink.params?.get('trailerId')).toBe('5'))
    expect(sink.params?.get('tractorId')).toBeNull()
  })

  it('filtra por estado anulado', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseWithdrawalsCapture(sink))
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText('Filtro de aceite XYZ')
    await user.selectOptions(screen.getByLabelText(/estado/i), 'CANCELLED')
    await waitFor(() => expect(sink.params?.get('status')).toBe('CANCELLED'))
  })

  it('el estado "Todos" omite el parámetro status', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseWithdrawalsCapture(sink))
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText('Filtro de aceite XYZ')
    await user.selectOptions(screen.getByLabelText(/estado/i), 'CANCELLED')
    await waitFor(() => expect(sink.params?.get('status')).toBe('CANCELLED'))
    await user.selectOptions(screen.getByLabelText(/estado/i), '')
    await waitFor(() => expect(sink.params?.get('status')).toBeNull())
  })

  it('filtra por rango de fechas', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseWithdrawalsCapture(sink))
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText('Filtro de aceite XYZ')
    await user.type(screen.getByLabelText(/desde/i), '2026-07-01')
    await user.type(screen.getByLabelText(/hasta/i), '2026-07-31')
    await waitFor(() => {
      expect(sink.params?.get('dateFrom')).toBe('2026-07-01')
      expect(sink.params?.get('dateTo')).toBe('2026-07-31')
    })
  })

  it('avisa del rango invertido y no consulta con él', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseWithdrawalsCapture(sink))
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText('Filtro de aceite XYZ')
    await user.type(screen.getByLabelText(/hasta/i), '2026-07-01')
    await waitFor(() => expect(sink.params?.get('dateTo')).toBe('2026-07-01'))
    await user.type(screen.getByLabelText(/desde/i), '2026-07-31')
    expect(await screen.findByRole('alert')).toHaveTextContent(/no puede ser posterior/i)
    await waitFor(() => expect(sink.params?.get('dateTo')).toBeNull())
    expect((sink.calls ?? []).every((params) => params.get('dateFrom') === null)).toBe(true)
  })

  it('reinicia a la primera página al cambiar un filtro', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseWithdrawalsPagedByParam(25, 10))
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText('Producto P0')
    await user.click(screen.getByLabelText('Página siguiente'))
    await screen.findByText('Producto P1')
    server.use(warehouseWithdrawalsCapture(sink))
    await user.selectOptions(screen.getByLabelText(/estado/i), 'ACTIVE')
    await waitFor(() => {
      expect(sink.params?.get('page')).toBe('0')
      expect(sink.params?.get('status')).toBe('ACTIVE')
    })
  })

  // ----- Paginación -----
  it('deshabilita "Página anterior" en la primera página', async () => {
    server.use(warehouseWithdrawalsPagedByParam(25, 10))
    renderRetiros()
    await screen.findByText('Producto P0')
    expect(screen.getByLabelText('Página anterior')).toBeDisabled()
  })

  it('pide la página siguiente al avanzar', async () => {
    server.use(warehouseWithdrawalsPagedByParam(25, 10))
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText('Producto P0')
    await user.click(screen.getByLabelText('Página siguiente'))
    expect(await screen.findByText('Producto P1')).toBeInTheDocument()
  })

  it('mantiene la tabla previa y avisa cuando falla el refetch al paginar', async () => {
    server.use(
      warehouseWithdrawalsOkThenErrorOnNextPage(
        [fakeWithdrawal({ receivedBy: fakeWorker({ fullName: 'Página Uno' }) })],
        { totalElements: 25, size: 10 },
      ),
    )
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText('Página Uno')
    await user.click(screen.getByLabelText('Página siguiente'))
    expect(await screen.findByText('Fallo al paginar')).toBeInTheDocument()
    expect(screen.getByText('Página Uno')).toBeInTheDocument()
  })

  // ----- Navegación -----
  it('el botón de registrar lleva al formulario de nuevo retiro', async () => {
    const user = userEvent.setup()
    renderRetiros()
    await screen.findByText('Aceite 15W40')
    await user.click(screen.getByRole('link', { name: /registrar retiro/i }))
    expect(await screen.findByText('NUEVO RETIRO STUB')).toBeInTheDocument()
  })

  // ----- A11y -----
  it('expone los encabezados de columna de la tabla', async () => {
    renderRetiros()
    await screen.findByText('Aceite 15W40')
    for (const header of ['Producto', 'Cantidad', 'Recibido por', 'Unidad', 'Fecha', 'Estado']) {
      expect(screen.getByRole('columnheader', { name: header })).toBeInTheDocument()
    }
  })

  it('presenta la pantalla con un único h1', async () => {
    renderRetiros()
    await screen.findByText('Aceite 15W40')
    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings).toHaveLength(1)
    expect(headings[0]).toHaveTextContent('Retiros')
  })

  it('rotula todos los controles de filtro', async () => {
    renderRetiros()
    await screen.findByText('Aceite 15W40')
    expect(screen.getByLabelText('Producto')).toBeInTheDocument()
    expect(screen.getByLabelText('Recibido por')).toBeInTheDocument()
    expect(screen.getByLabelText('Unidad de flota')).toBeInTheDocument()
    expect(screen.getByLabelText(/estado/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/desde/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/hasta/i)).toBeInTheDocument()
  })

  it('la fila del retiro lleva a su detalle', async () => {
    renderRetiros()
    const user = userEvent.setup()
    await user.click(
      await screen.findByRole('button', { name: /ver el retiro de aceite 15w40/i }),
    )
    expect(screen.getByText('DETALLE STUB')).toBeInTheDocument()
  })

  it('la fila del retiro se activa con el teclado (Enter)', async () => {
    renderRetiros()
    const user = userEvent.setup()
    const row = await screen.findByRole('button', {
      name: /ver el retiro de aceite 15w40/i,
    })
    row.focus()
    await user.keyboard('{Enter}')
    expect(screen.getByText('DETALLE STUB')).toBeInTheDocument()
  })

  it('no tiene violaciones de accesibilidad', async () => {
    const { container } = renderRetiros()
    await screen.findByText('Aceite 15W40')
    expect(await axe(container)).toHaveNoViolations()
  })
})
