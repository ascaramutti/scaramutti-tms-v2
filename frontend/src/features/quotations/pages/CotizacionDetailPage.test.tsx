import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CotizacionDetailPage } from './CotizacionDetailPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import {
  changeQuotationStatusSuccess,
  fakeItem,
  getQuotationResponse,
  quotationDetail,
  quotationDetailCapture,
  quotationDetailError,
  quotationDetailSlow,
} from '../../../test/mocks/handlers/quotations'
import type { ChangeStatusBody } from '../../../test/mocks/handlers/quotations'

function renderDetalle(initialPath = '/cotizaciones/1') {
  const queryClient = new QueryClient({
    // retryDelay 0: el hook reintenta errores ≠404 (1 vez); sin delay para que
    // el estado de error aparezca de inmediato en los tests.
    defaultOptions: { queries: { retry: false, retryDelay: 0 } },
  })
  // El detalle monta <QuotationDetailActions> (usa useAuth): sesión admin sembrada en cache.
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, fakeUser)
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/cotizaciones" element={<div>LISTADO COTIZACIONES</div>} />
            <Route path="/cotizaciones/:id" element={<CotizacionDetailPage />} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

/** El título (h1) de la pantalla es el código de la cotización. */
function findTitle(code = '2026-00001') {
  return screen.findByRole('heading', { level: 1, name: code })
}

describe('CotizacionDetailPage', () => {
  // ----- Render -----
  it('muestra el spinner durante la carga', async () => {
    server.use(quotationDetailSlow(getQuotationResponse(), 40))
    renderDetalle()
    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(await findTitle()).toBeInTheDocument()
  })

  it('renderiza cabecera, ítems y totales en el happy path', async () => {
    renderDetalle()
    expect(await findTitle()).toBeInTheDocument()
    expect(screen.getByText('ACME S.A.C.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Detalle de ítems' })).toBeInTheDocument()
  })

  it('muestra "Cotización no encontrada" en 404', async () => {
    server.use(quotationDetailError(404, { detail: 'No existe' }))
    renderDetalle('/cotizaciones/999')
    expect(await screen.findByText(/no encontrada/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /volver al listado/i })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 1, name: '2026-00001' })).not.toBeInTheDocument()
  })

  it('muestra el error del backend (Problem.detail) en 500', async () => {
    server.use(quotationDetailError(500, { detail: 'El servidor falló al obtener la cotización.' }))
    renderDetalle()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText('El servidor falló al obtener la cotización.')).toBeInTheDocument()
  })

  it('muestra "no encontrada" si el id de la URL no es numérico', async () => {
    renderDetalle('/cotizaciones/abc')
    expect(await screen.findByText(/no encontrada/i)).toBeInTheDocument()
  })

  // ----- Mapping de cabecera y tarjetas -----
  it('mapea código, cliente y RUC', async () => {
    server.use(
      quotationDetail(
        getQuotationResponse({
          code: '2026-00007',
          client: { id: 9, name: 'OTRO CLIENTE SAC', ruc: '20999999999' },
        }),
      ),
    )
    renderDetalle()
    expect(await findTitle('2026-00007')).toBeInTheDocument()
    expect(screen.getByText('OTRO CLIENTE SAC')).toBeInTheDocument()
    expect(screen.getByText(/20999999999/)).toBeInTheDocument()
  })

  it('muestra el contacto (nombre y teléfono)', async () => {
    server.use(quotationDetail(getQuotationResponse({ contactName: 'María Gómez', contactPhone: '912345678' })))
    renderDetalle()
    expect(await screen.findByText('María Gómez')).toBeInTheDocument()
    expect(screen.getByText('912345678')).toBeInTheDocument()
  })

  it('muestra moneda y condición de pago', async () => {
    server.use(
      quotationDetail(
        getQuotationResponse({
          currency: { id: 2, code: 'PEN', symbol: 'S/' },
          paymentTerm: { id: 1, name: 'Contado', days: 0 },
        }),
      ),
    )
    renderDetalle()
    await findTitle()
    expect(screen.getByText('PEN')).toBeInTheDocument()
    expect(screen.getByText('Contado')).toBeInTheDocument()
  })

  it('muestra la ruta origen ↓ destino en transporte', async () => {
    server.use(
      quotationDetail(getQuotationResponse({ quotationType: 'TRANSPORTE', origin: 'Callao', destination: 'Cusco' })),
    )
    renderDetalle()
    await findTitle()
    expect(screen.getByText('Callao')).toBeInTheDocument()
    expect(screen.getByText('Cusco')).toBeInTheDocument()
  })

  it('muestra "—" en la ruta cuando es alquiler (sin origen/destino)', async () => {
    server.use(
      quotationDetail(
        getQuotationResponse({
          quotationType: 'ALQUILER',
          origin: null,
          destination: null,
          items: [fakeItem()], // ítem simple: evita los "—" de P.Neto/IGV de los hijos del Integral
        }),
      ),
    )
    renderDetalle()
    await findTitle()
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('muestra validez y fecha de validez en la cabecera', async () => {
    server.use(quotationDetail(getQuotationResponse({ validityDays: 20, expiresAt: '2026-07-10T12:00:00Z' })))
    renderDetalle()
    await findTitle()
    expect(screen.getByText('20 días')).toBeInTheDocument()
    expect(screen.getByText(/Válida hasta 10\/07\/2026/)).toBeInTheDocument()
  })

  it('muestra la fecha tentativa de servicio sin corrimiento de zona (date-only)', async () => {
    server.use(quotationDetail(getQuotationResponse({ tentativeServiceDate: '2026-06-10' })))
    renderDetalle()
    await findTitle()
    // formatDateOnly evita el shift a 09/06 que daría formatDate con TZ Lima.
    expect(screen.getByText('10/06/2026')).toBeInTheDocument()
  })

  // ----- Badge de estado -----
  it('badge "Borrador" para DRAFT no vencida', async () => {
    server.use(quotationDetail(getQuotationResponse({ status: 'DRAFT', isExpired: false })))
    renderDetalle()
    await findTitle()
    expect(screen.getByText('Borrador')).toBeInTheDocument()
  })

  it('badge "Enviada" para SENT no vencida', async () => {
    server.use(quotationDetail(getQuotationResponse({ status: 'SENT', isExpired: false })))
    renderDetalle()
    await findTitle()
    expect(screen.getByText('Enviada')).toBeInTheDocument()
  })

  it('badge "Vencida" para el estado EXPIRED', async () => {
    server.use(quotationDetail(getQuotationResponse({ status: 'EXPIRED', isExpired: true })))
    renderDetalle()
    await findTitle()
    expect(screen.getByText('Vencida')).toBeInTheDocument()
  })

  it('badge "Aceptada" para el estado ACCEPTED', async () => {
    server.use(quotationDetail(getQuotationResponse({ status: 'ACCEPTED' })))
    renderDetalle()
    await findTitle()
    expect(screen.getByText('Aceptada')).toBeInTheDocument()
  })

  it('badge "Rechazada" para el estado REJECTED', async () => {
    server.use(quotationDetail(getQuotationResponse({ status: 'REJECTED', rejectionReason: 'Sin presupuesto' })))
    renderDetalle()
    await findTitle()
    // El header y la sección de motivo usan el mismo label "Rechazada"/"Motivo del rechazo";
    // basta con que el badge de estado esté presente.
    expect(screen.getAllByText('Rechazada').length).toBeGreaterThan(0)
  })

  // El badge ahora deriva del `status` (eje único): una DRAFT con `isExpired=true` por fecha
  // pero sin pasar por el job sigue mostrándose "Borrador" (el job la pondría EXPIRED).
  it('badge derivado del status: DRAFT con isExpired=true sigue "Borrador"', async () => {
    server.use(quotationDetail(getQuotationResponse({ status: 'DRAFT', isExpired: true })))
    renderDetalle()
    await findTitle()
    expect(screen.getByText('Borrador')).toBeInTheDocument()
    expect(screen.queryByText('Vencida')).not.toBeInTheDocument()
  })

  // ----- Totales -----
  it('muestra subtotal, IGV y total general en PEN', async () => {
    server.use(
      quotationDetail(
        getQuotationResponse({
          currency: { id: 2, code: 'PEN', symbol: 'S/' },
          items: [fakeItem({ subtotal: 100, unitPrice: 100, quantity: 1 })],
          totalSubtotal: 8474.58,
          totalIgv: 1525.42,
          totalAmount: 10000,
        }),
      ),
    )
    renderDetalle()
    await findTitle()
    expect(screen.getByText(/8,474\.58/)).toBeInTheDocument()
    expect(screen.getByText(/1,525\.42/)).toBeInTheDocument()
    expect(screen.getByText(/10,000\.00/)).toBeInTheDocument()
  })

  it('muestra el total general en USD', async () => {
    server.use(
      quotationDetail(
        getQuotationResponse({
          currency: { id: 1, code: 'USD', symbol: 'US$' },
          items: [fakeItem({ subtotal: 100, unitPrice: 100 })],
          totalSubtotal: 1694.92,
          totalIgv: 305.08,
          totalAmount: 2000,
        }),
      ),
    )
    renderDetalle()
    await findTitle()
    expect(screen.getByText(/2,000\.00/)).toBeInTheDocument()
  })

  // ----- Tabla de ítems (jerárquica) -----
  it('renderiza un ítem root simple con su precio neto', async () => {
    server.use(
      quotationDetail(
        getQuotationResponse({
          items: [
            fakeItem({
              id: 1,
              itemNumber: 1,
              serviceType: { id: 3, code: 'SPL', name: 'Transporte en plataforma', kind: 'SERVICIO' },
              quantity: 2,
              unitPrice: 500,
              subtotal: 1000,
            }),
          ],
        }),
      ),
    )
    renderDetalle()
    await findTitle()
    expect(screen.getByText('Transporte en plataforma')).toBeInTheDocument()
    expect(screen.getByText(/1,000\.00/)).toBeInTheDocument()
  })

  it('renderiza el Servicio Integral con sus hijos anidados', async () => {
    renderDetalle() // fixture default: Integral + hijos SPL y CES
    await findTitle()
    expect(screen.getByText('Servicio Integral')).toBeInTheDocument()
    expect(screen.getByText('Servicio de transporte en Plataforma')).toBeInTheDocument()
    expect(screen.getByText('Servicio de Escolta')).toBeInTheDocument()
    // Numeración jerárquica de presentación (del backend, displayLabel): hijos "1.a", "1.b".
    expect(screen.getByText('1.a')).toBeInTheDocument()
    expect(screen.getByText('1.b')).toBeInTheDocument()
    // Qué transporta el hijo de transporte (tipo de carga): clave para usar el Integral como referencia.
    expect(screen.getByText(/EXCAVADORA 326/)).toBeInTheDocument()
  })

  it('los hijos del Integral muestran su precio interno de referencia', async () => {
    renderDetalle() // hijos con internalReferencePrice 900 y 371.19
    await findTitle()
    expect(screen.getByText(/900\.00/)).toBeInTheDocument()
    expect(screen.getByText(/371\.19/)).toBeInTheDocument()
  })

  it('muestra la tabla de Stand-By de un ítem', async () => {
    server.use(
      quotationDetail(
        getQuotationResponse({ items: [fakeItem({ standby: { id: 1, pricePerDay: 150, includesIgv: true } })] }),
      ),
    )
    renderDetalle()
    await findTitle()
    expect(screen.getByRole('heading', { name: /stand-by/i })).toBeInTheDocument()
    expect(screen.getByText(/150\.00/)).toBeInTheDocument()
    expect(screen.getByText('Sí')).toBeInTheDocument()
  })

  it('muestra tipo de carga y observaciones de un ítem', async () => {
    server.use(
      quotationDetail(
        getQuotationResponse({
          items: [
            fakeItem({ cargoType: { id: 5, name: 'CONTENEDOR 40' }, observations: 'Frágil, manipular con cuidado' }),
          ],
        }),
      ),
    )
    renderDetalle()
    await findTitle()
    expect(screen.getByText(/CONTENEDOR 40/)).toBeInTheDocument()
    expect(screen.getByText(/Frágil, manipular con cuidado/)).toBeInTheDocument()
  })

  it('muestra dimensiones y peso cuando están presentes', async () => {
    server.use(
      quotationDetail(
        getQuotationResponse({
          items: [fakeItem({ weightKg: 1200, lengthMeters: 5, widthMeters: 2, heightMeters: 2.5 })],
        }),
      ),
    )
    renderDetalle()
    await findTitle()
    expect(screen.getByText(/1,200 kg/)).toBeInTheDocument()
    expect(screen.getByText(/5 × 2 × 2\.5 m/)).toBeInTheDocument()
  })

  // ----- Navegación -----
  it('el breadcrumb "Cotizaciones" navega al listado', async () => {
    const user = userEvent.setup()
    renderDetalle()
    await findTitle()
    await user.click(screen.getByRole('link', { name: /cotizaciones/i }))
    expect(await screen.findByText('LISTADO COTIZACIONES')).toBeInTheDocument()
  })

  it('usa el id de la URL en el fetch', async () => {
    const sink: { id?: string } = {}
    server.use(quotationDetailCapture(sink, getQuotationResponse({ id: 42, code: '2026-00042' })))
    renderDetalle('/cotizaciones/42')
    await findTitle('2026-00042')
    expect(sink.id).toBe('42')
  })

  // ----- A11y -----
  it('expone los headings de las secciones', async () => {
    renderDetalle()
    await findTitle()
    expect(screen.getByRole('heading', { name: 'Cliente' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Detalle de ítems' })).toBeInTheDocument()
  })

  it('el breadcrumb "Cotizaciones" es un link accesible', async () => {
    renderDetalle()
    await findTitle()
    expect(screen.getByRole('link', { name: /cotizaciones/i })).toBeInTheDocument()
  })

  // ----- Auditoría -----
  it('muestra la información de auditoría completa al pie', async () => {
    renderDetalle() // default: createdBy/updatedBy fullName "Admin TMS"
    await findTitle()
    expect(screen.getByText(/Elaborada por Admin TMS/i)).toBeInTheDocument()
    expect(screen.getByText(/Última edición por Admin TMS/i)).toBeInTheDocument()
  })

  // ----- Acciones del detalle -----
  it('monta las acciones del detalle (editar, previsualizar, descargar)', async () => {
    // Wiring: el detalle integra <QuotationDetailActions>. La lógica del PDF (descarga,
    // preview, error) se prueba aislada en QuotationDetailActions.test.tsx; acá solo
    // verificamos que el detalle efectivamente monta las acciones.
    renderDetalle()
    await findTitle()
    expect(screen.getByRole('link', { name: /editar/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /previsualizar pdf/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /descargar pdf/i })).toBeInTheDocument()
  })

  // ----- Transiciones de estado (integración) -----
  it('DRAFT → Enviada: badge "Enviada" y aparecen Aceptada/Rechazada', async () => {
    const user = userEvent.setup()
    // GET inicial DRAFT; el PATCH responde SENT (la cache del detalle se actualiza con esa
    // versión → el badge y los botones reflejan el nuevo estado sin refetch).
    server.use(quotationDetail(getQuotationResponse({ status: 'DRAFT' })))
    const sink: { body?: ChangeStatusBody } = {}
    server.use(changeQuotationStatusSuccess(sink))
    renderDetalle()
    await findTitle()

    expect(screen.getByText('Borrador')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /enviada/i }))

    expect(await screen.findByText('Enviada')).toBeInTheDocument()
    expect(sink.body?.status).toBe('SENT')
    expect(screen.getByRole('button', { name: /aceptada/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^rechazada$/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^enviada$/i })).not.toBeInTheDocument()
  })

  it('SENT → Rechazada: modal + motivo → badge "Rechazada", sección de motivo y sin botones', async () => {
    const user = userEvent.setup()
    server.use(quotationDetail(getQuotationResponse({ status: 'SENT' })))
    const sink: { body?: ChangeStatusBody } = {}
    server.use(changeQuotationStatusSuccess(sink))
    renderDetalle()
    await findTitle()

    expect(screen.getByText('Enviada')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^rechazada$/i }))

    // Modal abierto: escribo el motivo y confirmo.
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/motivo del rechazo/i), 'El cliente eligió otro proveedor')
    await user.click(within(dialog).getByRole('button', { name: /registrar rechazo/i }))

    // El detalle pasa a REJECTED: badge + sección de motivo + sin botones de transición.
    expect(await screen.findByRole('heading', { name: /motivo del rechazo/i })).toBeInTheDocument()
    expect(screen.getByText('El cliente eligió otro proveedor')).toBeInTheDocument()
    expect(sink.body).toEqual({ status: 'REJECTED', rejectionReason: 'El cliente eligió otro proveedor' })
    expect(screen.queryByRole('button', { name: /^rechazada$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /aceptada/i })).not.toBeInTheDocument()
  })

  it('estado terminal (ACCEPTED): no muestra botones de transición', async () => {
    server.use(quotationDetail(getQuotationResponse({ status: 'ACCEPTED' })))
    renderDetalle()
    await findTitle()
    expect(screen.queryByRole('button', { name: /^enviada$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /aceptada/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^rechazada$/i })).not.toBeInTheDocument()
  })
})
