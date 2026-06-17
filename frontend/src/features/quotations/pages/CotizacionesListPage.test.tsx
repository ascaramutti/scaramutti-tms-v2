import { describe, expect, it, beforeEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CotizacionesListPage } from './CotizacionesListPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import {
  fakeQuotation,
  quotationsCapture,
  quotationsEmpty,
  quotationsError,
  quotationsOkThenErrorOnNextPage,
  quotationsPage,
  quotationsPagedByParam,
  quotationsSlow,
} from '../../../test/mocks/handlers/quotations'

// Stub del detalle: refleja el :id para verificar la navegación con el valor correcto.
function DetalleStub() {
  const { id } = useParams()
  return <div>DETALLE {id}</div>
}

function renderCotizaciones(initialPath = '/cotizaciones') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  // La página vive dentro del layout autenticado: sesión admin sembrada en cache.
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, fakeUser)
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/cotizaciones" element={<CotizacionesListPage />} />
            <Route path="/cotizaciones/nueva" element={<div>NUEVA COTIZACION</div>} />
            <Route path="/cotizaciones/:id" element={<DetalleStub />} />
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

describe('CotizacionesListPage', () => {
  beforeEach(() => {
    // El polyfill de localStorage se limpia en setup.ts; nada extra acá.
  })

  // ----- Render -----
  it('muestra el spinner durante la carga inicial', async () => {
    server.use(quotationsSlow([fakeQuotation()], 40))
    renderCotizaciones()
    expect(screen.getByRole('status')).toBeInTheDocument()
    // Esperamos la resolución para no dejar el fetch colgado tras el unmount.
    expect(await screen.findByText('2026-00001')).toBeInTheDocument()
  })

  it('renderiza las filas de la página (happy path)', async () => {
    renderCotizaciones()
    expect(await screen.findByText('2026-00001')).toBeInTheDocument()
    expect(screen.getByText('2026-00002')).toBeInTheDocument()
    expect(screen.getByText('2026-00003')).toBeInTheDocument()
  })

  it('muestra el estado vacío de base cuando no hay cotizaciones ni filtros', async () => {
    server.use(quotationsEmpty())
    renderCotizaciones()
    expect(await screen.findByText(/aún no hay cotizaciones/i)).toBeInTheDocument()
  })

  it('muestra el estado vacío de filtros cuando hay filtros activos y cero resultados', async () => {
    server.use(quotationsEmpty())
    const user = userEvent.setup()
    renderCotizaciones()
    await screen.findByText(/aún no hay cotizaciones/i)
    await user.selectOptions(screen.getByLabelText(/estado/i), 'SENT')
    expect(await screen.findByText(/no se encontraron cotizaciones/i)).toBeInTheDocument()
  })

  it('muestra el error del backend y permite reintentar', async () => {
    const user = userEvent.setup()
    server.use(quotationsError(500, { detail: 'El servidor falló al listar.' }))
    renderCotizaciones()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText('El servidor falló al listar.')).toBeInTheDocument()
    // Reintento exitoso: cambiamos el handler a success y clickeamos "Reintentar".
    server.use(quotationsPage([fakeQuotation({ code: '2026-00001' })]))
    await user.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByText('2026-00001')).toBeInTheDocument()
  })

  it('ante un error al paginar mantiene la tabla previa y muestra un aviso', async () => {
    const user = userEvent.setup()
    server.use(
      quotationsOkThenErrorOnNextPage([fakeQuotation({ code: '2026-00001' })], {
        totalElements: 25,
        size: 10,
      }),
    )
    renderCotizaciones()
    await screen.findByText('2026-00001')
    await user.click(screen.getByLabelText('Página siguiente'))
    // El refetch de la página 2 falla, pero la fila previa NO se borra (keepPreviousData)
    // y el error se muestra como aviso no destructivo.
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText('2026-00001')).toBeInTheDocument()
    // El footer refleja la página realmente mostrada (la 0 retenida), no la que falló:
    // "Mostrando 1–1 de 25", nunca "11–11".
    expect(screen.getByText(/Mostrando 1[–-]1 de 25/)).toBeInTheDocument()
    expect(screen.queryByText(/Mostrando 11/)).not.toBeInTheDocument()
  })

  // ----- Mapping de campos -----
  it('mapea los campos núcleo de una cotización en su fila', async () => {
    server.use(
      quotationsPage([
        fakeQuotation({
          code: '2026-00007',
          client: { id: 9, name: 'ACME S.A.C.', ruc: '20123456789' },
          itemsCount: 3,
          totalAmount: 1500.5,
          currencyCode: 'PEN',
          createdAt: '2026-05-20T10:00:00Z',
        }),
      ]),
    )
    renderCotizaciones()
    const row = rowOf(await screen.findByText('2026-00007'))
    expect(within(row).getByText('ACME S.A.C.')).toBeInTheDocument()
    expect(within(row).getByText('20123456789')).toBeInTheDocument()
    expect(within(row).getByText('3')).toBeInTheDocument()
    // Monto: matcheamos el número para no acoplar el test al separador NBSP de Intl.
    expect(within(row).getByText(/1,500\.50/)).toBeInTheDocument()
    expect(within(row).getByText('20/05/2026')).toBeInTheDocument()
  })

  it('muestra el tipo de cotización en español', async () => {
    server.use(
      quotationsPage([
        fakeQuotation({ id: 1, code: '2026-00001', quotationType: 'TRANSPORTE' }),
        fakeQuotation({
          id: 2,
          code: '2026-00002',
          quotationType: 'ALQUILER',
          origin: null,
          destination: null,
        }),
      ]),
    )
    renderCotizaciones()
    const rowTransporte = rowOf(await screen.findByText('2026-00001'))
    const rowAlquiler = rowOf(screen.getByText('2026-00002'))
    // within(row) evita la colisión con las <option> del filtro de tipo.
    expect(within(rowTransporte).getByText('Transporte')).toBeInTheDocument()
    expect(within(rowAlquiler).getByText('Alquiler')).toBeInTheDocument()
  })

  it('muestra "—" en la ruta cuando no hay origen/destino (alquiler)', async () => {
    server.use(
      quotationsPage([
        fakeQuotation({ code: '2026-00001', origin: null, destination: null }),
      ]),
    )
    renderCotizaciones()
    const row = rowOf(await screen.findByText('2026-00001'))
    expect(within(row).getByText('—')).toBeInTheDocument()
  })

  it('formatea el total en USD', async () => {
    server.use(
      quotationsPage([
        fakeQuotation({ code: '2026-00009', totalAmount: 2000, currencyCode: 'USD' }),
      ]),
    )
    renderCotizaciones()
    const row = rowOf(await screen.findByText('2026-00009'))
    expect(within(row).getByText(/2,000\.00/)).toBeInTheDocument()
  })

  // ----- Badge de estado -----
  it('badge "Borrador" para DRAFT no vencida', async () => {
    server.use(
      quotationsPage([fakeQuotation({ code: '2026-00001', status: 'DRAFT', isExpired: false })]),
    )
    renderCotizaciones()
    const row = rowOf(await screen.findByText('2026-00001'))
    expect(within(row).getByText('Borrador')).toBeInTheDocument()
  })

  it('badge "Enviada" para SENT no vencida', async () => {
    server.use(
      quotationsPage([fakeQuotation({ code: '2026-00001', status: 'SENT', isExpired: false })]),
    )
    renderCotizaciones()
    const row = rowOf(await screen.findByText('2026-00001'))
    expect(within(row).getByText('Enviada')).toBeInTheDocument()
  })

  it('badge "Vencida" para el estado EXPIRED', async () => {
    server.use(
      quotationsPage([fakeQuotation({ code: '2026-00001', status: 'EXPIRED', isExpired: true })]),
    )
    renderCotizaciones()
    const row = rowOf(await screen.findByText('2026-00001'))
    expect(within(row).getByText('Vencida')).toBeInTheDocument()
  })

  it('badges "Aceptada"/"Rechazada" para los estados terminales', async () => {
    server.use(
      quotationsPage([
        fakeQuotation({ id: 1, code: '2026-00001', status: 'ACCEPTED' }),
        fakeQuotation({ id: 2, code: '2026-00002', status: 'REJECTED' }),
      ]),
    )
    renderCotizaciones()
    expect(within(rowOf(await screen.findByText('2026-00001'))).getByText('Aceptada')).toBeInTheDocument()
    expect(within(rowOf(screen.getByText('2026-00002'))).getByText('Rechazada')).toBeInTheDocument()
  })

  // El badge ahora deriva del status: una DRAFT con isExpired=true por fecha (pero sin pasar
  // por el job) sigue mostrándose "Borrador".
  it('badge derivado del status: DRAFT con isExpired=true sigue "Borrador"', async () => {
    server.use(
      quotationsPage([fakeQuotation({ code: '2026-00001', status: 'DRAFT', isExpired: true })]),
    )
    renderCotizaciones()
    const row = rowOf(await screen.findByText('2026-00001'))
    expect(within(row).getByText('Borrador')).toBeInTheDocument()
    expect(within(row).queryByText('Vencida')).not.toBeInTheDocument()
  })

  // ----- Búsqueda (gate minLength 3) -----
  it('no envía el parámetro q con menos de 3 caracteres y muestra el hint', async () => {
    const user = userEvent.setup()
    const requests: string[] = []
    const onRequest = ({ request }: { request: Request }) => requests.push(request.url)
    server.events.on('request:start', onRequest)
    renderCotizaciones()
    await screen.findByText('2026-00001')
    await user.type(screen.getByLabelText(/buscar/i), 'ac')
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    // Esperamos más que el debounce para confirmar la ausencia de fetch con q.
    // act() envuelve el setState que dispara el debounce al vencer el timer.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 450))
    })
    expect(requests.some((url) => url.includes('q='))).toBe(false)
    server.events.removeListener('request:start', onRequest)
  })

  it('envía el parámetro q con 3 o más caracteres', async () => {
    const user = userEvent.setup()
    const sink: { params?: URLSearchParams } = {}
    server.use(quotationsCapture(sink, [fakeQuotation()]))
    renderCotizaciones()
    await user.type(screen.getByLabelText(/buscar/i), 'acme')
    await waitFor(() => expect(sink.params?.get('q')).toBe('acme'))
  })

  // ----- Filtros -----
  it('filtra por estado (param status)', async () => {
    const user = userEvent.setup()
    const sink: { params?: URLSearchParams } = {}
    server.use(quotationsCapture(sink, [fakeQuotation()]))
    renderCotizaciones()
    await user.selectOptions(screen.getByLabelText(/estado/i), 'SENT')
    await waitFor(() => expect(sink.params?.get('status')).toBe('SENT'))
  })

  it('el filtro de estado ofrece los 5 estados + "Todos"', async () => {
    renderCotizaciones()
    await screen.findByText('2026-00001')
    const select = screen.getByLabelText(/estado/i)
    const labels = Array.from(select.querySelectorAll('option')).map((o) => o.textContent)
    expect(labels).toEqual(['Todos', 'Borrador', 'Enviada', 'Aceptada', 'Rechazada', 'Vencida'])
  })

  it('filtra por "Vencida" → GET ?status=EXPIRED', async () => {
    const user = userEvent.setup()
    const sink: { params?: URLSearchParams } = {}
    server.use(quotationsCapture(sink, [fakeQuotation()]))
    renderCotizaciones()
    await user.selectOptions(screen.getByLabelText(/estado/i), 'EXPIRED')
    await waitFor(() => expect(sink.params?.get('status')).toBe('EXPIRED'))
  })

  it('filtra por tipo (param quotationType)', async () => {
    const user = userEvent.setup()
    const sink: { params?: URLSearchParams } = {}
    server.use(quotationsCapture(sink, [fakeQuotation()]))
    renderCotizaciones()
    await user.selectOptions(screen.getByLabelText(/tipo/i), 'ALQUILER')
    await waitFor(() => expect(sink.params?.get('quotationType')).toBe('ALQUILER'))
  })

  it('filtra por fecha desde (param dateFrom)', async () => {
    const sink: { params?: URLSearchParams } = {}
    server.use(quotationsCapture(sink, [fakeQuotation()]))
    renderCotizaciones()
    fireEvent.change(screen.getByLabelText(/desde/i), { target: { value: '2026-05-01' } })
    await waitFor(() => expect(sink.params?.get('dateFrom')).toBe('2026-05-01'))
  })

  it('filtra por fecha hasta (param dateTo)', async () => {
    const sink: { params?: URLSearchParams } = {}
    server.use(quotationsCapture(sink, [fakeQuotation()]))
    renderCotizaciones()
    fireEvent.change(screen.getByLabelText(/hasta/i), { target: { value: '2026-05-31' } })
    await waitFor(() => expect(sink.params?.get('dateTo')).toBe('2026-05-31'))
  })

  it('con rango de fechas inválido muestra alerta y no envía un rango invertido', async () => {
    const requests: string[] = []
    const onRequest = ({ request }: { request: Request }) => requests.push(request.url)
    server.events.on('request:start', onRequest)
    renderCotizaciones()
    await screen.findByText('2026-00001')
    // "hasta" anterior a "desde" → rango inválido.
    fireEvent.change(screen.getByLabelText(/desde/i), { target: { value: '2026-05-31' } })
    fireEvent.change(screen.getByLabelText(/hasta/i), { target: { value: '2026-05-01' } })
    expect(await screen.findByText(/no puede ser anterior/i)).toBeInTheDocument()
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 100))
    })
    // Ninguna request debe haber salido con un rango invertido (dateFrom > dateTo).
    const sentInvertedRange = requests.some((url) => {
      const params = new URL(url).searchParams
      const from = params.get('dateFrom')
      const to = params.get('dateTo')
      return from !== null && to !== null && from > to
    })
    expect(sentInvertedRange).toBe(false)
    server.events.removeListener('request:start', onRequest)
  })

  it('reinicia a la primera página al cambiar un filtro', async () => {
    const user = userEvent.setup()
    const sink: { params?: URLSearchParams } = {}
    server.use(quotationsCapture(sink, [fakeQuotation()], { totalElements: 25, size: 10 }))
    renderCotizaciones()
    await screen.findByText('2026-00001')
    await user.click(screen.getByLabelText('Página siguiente'))
    await waitFor(() => expect(sink.params?.get('page')).toBe('1'))
    await user.selectOptions(screen.getByLabelText(/estado/i), 'SENT')
    await waitFor(() => {
      expect(sink.params?.get('page')).toBe('0')
      expect(sink.params?.get('status')).toBe('SENT')
    })
  })

  // ----- Paginación -----
  it('deshabilita "Página anterior" en la primera página', async () => {
    server.use(quotationsPage([fakeQuotation()], { totalElements: 25, size: 10, page: 0 }))
    renderCotizaciones()
    await screen.findByText('2026-00001')
    expect(screen.getByLabelText('Página anterior')).toBeDisabled()
    expect(screen.getByLabelText('Página siguiente')).toBeEnabled()
  })

  it('deshabilita "Página siguiente" en la última página', async () => {
    const user = userEvent.setup()
    server.use(quotationsPagedByParam(15, 10))
    renderCotizaciones()
    await screen.findByText('P0')
    await user.click(screen.getByLabelText('Página siguiente'))
    await screen.findByText('P1')
    expect(screen.getByLabelText('Página siguiente')).toBeDisabled()
    expect(screen.getByLabelText('Página anterior')).toBeEnabled()
  })

  it('al avanzar de página pide la página siguiente (param page=1)', async () => {
    const user = userEvent.setup()
    const sink: { params?: URLSearchParams } = {}
    server.use(quotationsCapture(sink, [fakeQuotation()], { totalElements: 25, size: 10 }))
    renderCotizaciones()
    await screen.findByText('2026-00001')
    await user.click(screen.getByLabelText('Página siguiente'))
    await waitFor(() => expect(sink.params?.get('page')).toBe('1'))
  })

  it('envía size=10 por defecto', async () => {
    const sink: { params?: URLSearchParams } = {}
    server.use(quotationsCapture(sink, [fakeQuotation()]))
    renderCotizaciones()
    await waitFor(() => expect(sink.params?.get('size')).toBe('10'))
  })

  // ----- Navegación -----
  it('al hacer click en una fila navega al detalle', async () => {
    const user = userEvent.setup()
    server.use(quotationsPage([fakeQuotation({ id: 42, code: '2026-00042' })]))
    renderCotizaciones()
    await user.click(await screen.findByText('2026-00042'))
    expect(await screen.findByText('DETALLE 42')).toBeInTheDocument()
  })

  it('el botón "Nueva cotización" navega al wizard', async () => {
    const user = userEvent.setup()
    renderCotizaciones()
    await user.click(screen.getByRole('button', { name: /nueva cotización/i }))
    expect(await screen.findByText('NUEVA COTIZACION')).toBeInTheDocument()
  })

  // ----- A11y -----
  it('la tabla expone los encabezados de columna', async () => {
    renderCotizaciones()
    await screen.findByText('2026-00001')
    expect(screen.getByRole('columnheader', { name: 'Código' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Cliente' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Total' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Estado' })).toBeInTheDocument()
  })

  it('el campo de búsqueda tiene label accesible', async () => {
    renderCotizaciones()
    await screen.findByText('2026-00001')
    expect(screen.getByLabelText(/buscar/i)).toBeInTheDocument()
  })
})
