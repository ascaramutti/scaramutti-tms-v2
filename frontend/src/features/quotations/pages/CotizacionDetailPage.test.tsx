import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CotizacionDetailPage } from './CotizacionDetailPage'
import { server } from '../../../test/mocks/server'
import {
  fakeItem,
  getQuotationResponse,
  quotationDetail,
  quotationDetailCapture,
  quotationDetailError,
  quotationDetailSlow,
} from '../../../test/mocks/handlers/quotations'

function renderDetalle(initialPath = '/cotizaciones/1') {
  const queryClient = new QueryClient({
    // retryDelay 0: el hook reintenta errores ≠404 (1 vez); sin delay para que
    // el estado de error aparezca de inmediato en los tests.
    defaultOptions: { queries: { retry: false, retryDelay: 0 } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/cotizaciones" element={<div>LISTADO COTIZACIONES</div>} />
          <Route path="/cotizaciones/:id" element={<CotizacionDetailPage />} />
        </Routes>
      </MemoryRouter>
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

  it('badge "Vencida" cuando isExpired aunque el status sea DRAFT', async () => {
    server.use(quotationDetail(getQuotationResponse({ status: 'DRAFT', isExpired: true })))
    renderDetalle()
    await findTitle()
    expect(screen.getByText('Vencida')).toBeInTheDocument()
    expect(screen.queryByText('Borrador')).not.toBeInTheDocument()
  })

  it('badge "Vencida" cuando isExpired y status SENT', async () => {
    server.use(quotationDetail(getQuotationResponse({ status: 'SENT', isExpired: true })))
    renderDetalle()
    await findTitle()
    expect(screen.getByText('Vencida')).toBeInTheDocument()
    expect(screen.queryByText('Enviada')).not.toBeInTheDocument()
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
})
