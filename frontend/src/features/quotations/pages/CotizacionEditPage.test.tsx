import { describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CotizacionEditPage } from './CotizacionEditPage'
import { server } from '../../../test/mocks/server'
import {
  fakeItem,
  getQuotationResponse,
  quotationDetail,
  quotationDetailError,
  quotationDetailSlow,
  updateQuotationError,
  updateQuotationSlow,
  updateQuotationSuccess,
} from '../../../test/mocks/handlers/quotations'
import type { QuotationRequest } from '../../../api'

/** Stub del detalle: muestra el id de la URL (para verificar la navegación post-guardado). */
function QuotationDetailStub() {
  const { id } = useParams()
  return <div>DETALLE COTIZACION {id}</div>
}

function renderEdit(id: string | number = 1) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/cotizaciones/${id}/editar`]}>
        <Routes>
          <Route path="/cotizaciones" element={<div>LISTADO COTIZACIONES</div>} />
          <Route path="/cotizaciones/:id/editar" element={<CotizacionEditPage />} />
          <Route path="/cotizaciones/:id" element={<QuotationDetailStub />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Espera a que el form (Step 1) esté montado tras cargar cotización + catálogos. */
function waitForForm() {
  return screen.findByText('Tipo de cotización')
}

/** Cotización editable: sin fecha tentativa pasada (que bloquearía el submit). */
function editableQuotation(overrides = {}) {
  return getQuotationResponse({ tentativeServiceDate: null, ...overrides })
}

describe('CotizacionEditPage', () => {
  // ----- Pre-carga / render -----

  it('muestra el spinner mientras carga la cotización', async () => {
    server.use(quotationDetailSlow(getQuotationResponse(), 40))
    renderEdit()
    expect(screen.getByRole('status')).toBeInTheDocument()
    await waitForForm()
  })

  it('monta el form en modo edición (heading "Editar cotización")', async () => {
    server.use(quotationDetail(getQuotationResponse({ code: '2026-00007' })))
    renderEdit()
    expect(await screen.findByText(/editar cotización 2026-00007/i)).toBeInTheDocument()
    expect(screen.getByText('Tipo de cotización')).toBeInTheDocument()
  })

  it('pre-carga el cliente y el contacto (snapshot)', async () => {
    server.use(
      quotationDetail(
        getQuotationResponse({
          client: { id: 1, name: 'ACME S.A.C.', ruc: '20123456789' },
          contactName: 'Juan Pérez',
          contactPhone: '987654321',
        }),
      ),
    )
    renderEdit()
    await waitForForm()
    expect((screen.getByLabelText('Cliente de la cotización') as HTMLInputElement).value).toBe(
      'ACME S.A.C.',
    )
    expect((screen.getByLabelText(/persona de contacto/i) as HTMLInputElement).value).toBe('Juan Pérez')
    expect((screen.getByLabelText(/teléfono de contacto/i) as HTMLInputElement).value).toBe('987654321')
  })

  it('pre-carga las condiciones comerciales (moneda, validez)', async () => {
    server.use(
      quotationDetail(
        getQuotationResponse({ currency: { id: 2, code: 'PEN', symbol: 'S/' }, validityDays: 20 }),
      ),
    )
    renderEdit()
    await waitForForm()
    expect((screen.getByLabelText('Moneda') as HTMLSelectElement).value).toBe('2')
    expect((screen.getByLabelText(/validez/i) as HTMLInputElement).value).toBe('20')
  })

  it('pre-carga la ruta en una cotización TRANSPORTE', async () => {
    server.use(
      quotationDetail(
        getQuotationResponse({ quotationType: 'TRANSPORTE', origin: 'Lima', destination: 'Arequipa' }),
      ),
    )
    renderEdit()
    await waitForForm()
    expect((screen.getByLabelText('Origen') as HTMLInputElement).value).toBe('Lima')
    expect((screen.getByLabelText('Destino') as HTMLInputElement).value).toBe('Arequipa')
  })

  it('pre-carga la jerarquía del Servicio Integral (visible en el Resumen)', async () => {
    const user = userEvent.setup()
    // Ids alineados con el catálogo de test (4=INT, 1=SCB/SERVICIO, 2=CES/COMPLEMENTARIO) para
    // que el Resumen resuelva los nombres del catálogo.
    server.use(
      quotationDetail(
        getQuotationResponse({
          items: [
            fakeItem({
              id: 1,
              serviceType: { id: 4, code: 'INT', name: 'Servicio Integral', kind: 'INTEGRAL' },
              unitPrice: 1500,
              children: [
                fakeItem({
                  id: 2,
                  serviceType: { id: 1, code: 'SCB', name: 'Transporte de carga general', kind: 'SERVICIO' },
                  cargoType: { id: 7, name: 'EXCAVADORA' },
                  weightKg: 25900,
                  unitPrice: 0,
                  internalReferencePrice: 900,
                }),
                fakeItem({
                  id: 3,
                  serviceType: { id: 2, code: 'CES', name: 'Escolta armada', kind: 'COMPLEMENTARIO' },
                  unitPrice: 0,
                  internalReferencePrice: 371,
                }),
              ],
            }),
          ],
        }),
      ),
    )
    renderEdit()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    expect(screen.getByText('Servicio Integral')).toBeInTheDocument()
    expect(screen.getByText('Transporte de carga general')).toBeInTheDocument()
    expect(screen.getByText('Escolta armada')).toBeInTheDocument()
  })

  // ----- 404 / id inválido -----

  it('muestra "no encontrada" si el GET devuelve 404', async () => {
    server.use(quotationDetailError(404, { code: 'QUO-003', detail: 'No existe' }))
    renderEdit(999)
    expect(await screen.findByText(/no encontrada/i)).toBeInTheDocument()
    expect(screen.queryByText('Tipo de cotización')).not.toBeInTheDocument()
  })

  it('muestra "no encontrada" si el id no es numérico', async () => {
    renderEdit('abc')
    expect(await screen.findByText(/no encontrada/i)).toBeInTheDocument()
  })

  // ----- Inmutables -----

  it('deshabilita el selector de tipo de cotización', async () => {
    server.use(quotationDetail(getQuotationResponse({ quotationType: 'TRANSPORTE' })))
    renderEdit()
    await waitForForm()
    expect(screen.getByRole('button', { name: /transporte/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /alquiler/i })).toBeDisabled()
  })

  it('muestra el cliente read-only (sin combobox de búsqueda)', async () => {
    server.use(quotationDetail(getQuotationResponse({ client: { id: 1, name: 'ACME S.A.C.', ruc: '20123456789' } })))
    renderEdit()
    await waitForForm()
    expect(screen.queryByPlaceholderText(/busca por nombre o ruc/i)).not.toBeInTheDocument()
    expect(screen.getByText(/no se puede cambiar al editar/i)).toBeInTheDocument()
  })

  // ----- Edición + submit (PUT) -----

  it('edita la validez y guarda: PUT con el body actualizado + navega al detalle', async () => {
    const user = userEvent.setup()
    const sink: { body?: QuotationRequest; ifMatch?: string | null } = {}
    server.use(
      quotationDetail(editableQuotation({ validityDays: 15 })),
      updateQuotationSuccess(sink),
    )
    renderEdit(1)
    await waitForForm()

    const validez = screen.getByLabelText(/validez/i)
    await user.clear(validez)
    await user.type(validez, '30')

    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))

    expect(await screen.findByText(/DETALLE COTIZACION 1/i)).toBeInTheDocument()
    expect(sink.body?.validityDays).toBe(30)
    // Inmutables intactos en el payload.
    expect(sink.body?.quotationType).toBe('TRANSPORTE')
    expect(sink.body?.clientId).toBe(1)
  })

  it('manda el If-Match con el ETag del header del GET, no el updatedAt del body', async () => {
    const user = userEvent.setup()
    const sink: { body?: QuotationRequest; ifMatch?: string | null } = {}
    // En producción el ETag del header (6 decimales) difiere del updatedAt del body (5 decimales,
    // Jackson recorta el cero final). El front DEBE reenviar el header opaco, no reconstruir del body.
    server.use(
      quotationDetail(
        editableQuotation({ updatedAt: '2026-05-20T10:00:00.39289Z' }),
        '"2026-05-20T10:00:00.392890Z"',
      ),
      updateQuotationSuccess(sink),
    )
    renderEdit(1)
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))

    await screen.findByText(/DETALLE COTIZACION 1/i)
    expect(sink.ifMatch).toBe('"2026-05-20T10:00:00.392890Z"') // el header (6 dec), NO el body (.39289)
  })

  it('deshabilita "Guardar cambios" mientras el PUT está en curso', async () => {
    const user = userEvent.setup()
    server.use(quotationDetail(editableQuotation()), updateQuotationSlow(40))
    renderEdit()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    const saveButton = screen.getByRole('button', { name: /guardar cambios/i })
    await user.click(saveButton)
    expect(screen.getByRole('button', { name: /guardando/i })).toBeDisabled()
    await screen.findByText(/DETALLE COTIZACION/i)
  })

  // ----- 412 conflicto -----

  it('muestra el mensaje de conflicto (412) y ofrece recargar', async () => {
    const user = userEvent.setup()
    server.use(
      quotationDetail(editableQuotation()),
      updateQuotationError(412, {
        code: 'COM-004',
        detail: 'El recurso fue modificado por otro usuario. Recargue antes de editar.',
      }),
    )
    renderEdit()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))

    expect(await screen.findByText(/modificado por otro usuario/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /recargar cotización/i })).toBeInTheDocument()
    // No navega: sigue en el wizard.
    expect(screen.queryByText(/DETALLE COTIZACION/i)).not.toBeInTheDocument()
  })

  it('al recargar tras un 412 re-precarga la versión fresca y limpia el conflicto', async () => {
    const user = userEvent.setup()
    server.use(
      quotationDetail(editableQuotation({ validityDays: 15, updatedAt: '2026-05-20T10:00:00Z' })),
      updateQuotationError(412, {
        code: 'COM-004',
        detail: 'El recurso fue modificado por otro usuario. Recargue antes de editar.',
      }),
    )
    renderEdit()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await screen.findByText(/modificado por otro usuario/i)

    // El siguiente GET devuelve la versión fresca (validez 99, nuevo updatedAt).
    server.use(quotationDetail(editableQuotation({ validityDays: 99, updatedAt: '2026-05-21T12:00:00Z' })))
    await user.click(screen.getByRole('button', { name: /recargar cotización/i }))

    // El form remonta (key) en el Step 1 con la validez nueva; el banner desaparece.
    await waitFor(() =>
      expect((screen.getByLabelText(/validez/i) as HTMLInputElement).value).toBe('99'),
    )
    expect(screen.queryByText(/modificado por otro usuario/i)).not.toBeInTheDocument()
  })

  it('al recargar vuelve al primer step aunque la versión no haya cambiado', async () => {
    const user = userEvent.setup()
    server.use(
      quotationDetail(editableQuotation({ updatedAt: '2026-05-20T10:00:00Z' })),
      updateQuotationError(412, {
        code: 'COM-004',
        detail: 'El recurso fue modificado por otro usuario. Recargue antes de editar.',
      }),
    )
    renderEdit()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await screen.findByText(/modificado por otro usuario/i)

    // Recargar con la MISMA versión (nadie editó): igual debe reiniciar al primer paso.
    server.use(quotationDetail(editableQuotation({ updatedAt: '2026-05-20T10:00:00Z' })))
    await user.click(screen.getByRole('button', { name: /recargar cotización/i }))

    // Vuelve al Step 1 (su heading reaparece) y ya no está en el Resumen.
    expect(await screen.findByText('Tipo de cotización')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /resumen final/i })).not.toBeInTheDocument()
  })

  // ----- 400 validación -----

  it('muestra el detail del backend ante un 400', async () => {
    const user = userEvent.setup()
    server.use(
      quotationDetail(editableQuotation()),
      updateQuotationError(400, { code: 'COM-001', detail: 'El origen es obligatorio para transporte.' }),
    )
    renderEdit()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))

    expect(await screen.findByText(/el origen es obligatorio para transporte/i)).toBeInTheDocument()
    expect(screen.queryByText(/DETALLE COTIZACION/i)).not.toBeInTheDocument()
  })
})
