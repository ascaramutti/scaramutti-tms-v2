import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { QuotationDetailActions } from './QuotationDetailActions'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import {
  changeQuotationStatusError,
  changeQuotationStatusSlow,
  changeQuotationStatusSuccess,
  quotationPdf,
  quotationPdfError,
  quotationPdfSlow,
} from '../../../test/mocks/handlers/quotations'
import type { ChangeStatusBody } from '../../../test/mocks/handlers/quotations'
import type { QuotationStatus, UserRole } from '../../../api'
import { buttonClasses } from '../../../shared/ui/buttonClasses'

interface RenderOptions {
  status?: QuotationStatus
  etag?: string | null
  role?: UserRole
  onRefetch?: () => void
}

function renderActions({ status = 'DRAFT', etag = '"v1"', role = 'admin', onRefetch }: RenderOptions = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, { ...fakeUser, role })
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter>
          <QuotationDetailActions
            quotationId={1}
            quotationCode="2026-00001"
            status={status}
            etag={etag}
            onRefetch={onRefetch ?? (() => {})}
          />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

describe('QuotationDetailActions', () => {
  beforeEach(() => {
    // jsdom no implementa la API de object URLs.
    URL.createObjectURL = vi.fn(() => 'blob:mock-url')
    URL.revokeObjectURL = vi.fn()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  // ----- PDF (sin cambios de comportamiento) -----
  it('renderiza los botones Previsualizar y Descargar, con Descargar como principal', () => {
    renderActions()
    const previsualizar = screen.getByRole('button', { name: /previsualizar pdf/i })
    const descargar = screen.getByRole('button', { name: /descargar pdf/i })
    expect(previsualizar).toBeInTheDocument()
    expect(descargar).toBeInTheDocument()
    // La jerarquía entre los dos es la decisión, no la presencia: descargar es lo que el
    // usuario viene a hacer y previsualizar es el paso previo. Intercambiarlos deja los
    // veintidós casos de este archivo en verde: medido. Por inclusión, porque los dos suman
    // sus clases de `disabled:`.
    const clases = (el: HTMLElement) => new Set(el.className.split(/\s+/))
    expect(clases(descargar).has('bg-accent')).toBe(true)
    expect(clases(previsualizar).has('bg-accent')).toBe(false)
    expect(clases(previsualizar).has('border-border-strong')).toBe(true)
  })

  it('muestra Editar como enlace secundario al wizard de edición', () => {
    renderActions()
    expect(screen.getByRole('link', { name: /editar/i })).toHaveAttribute('href', '/cotizaciones/1/editar')
    // Con su variante: es el secundario de la barra, al lado del azul de Descargar PDF.
    expect(screen.getByRole('link', { name: /editar/i }).className).toBe(
      buttonClasses({ variant: 'secondary' }),
    )
  })

  it('estado editable (SENT): Editar sigue siendo un link al wizard', () => {
    renderActions({ status: 'SENT' })
    expect(screen.getByRole('link', { name: /editar/i })).toHaveAttribute('href', '/cotizaciones/1/editar')
  })

  it.each([
    ['ACCEPTED', 'aceptada'],
    ['REJECTED', 'rechazada'],
    ['EXPIRED', 'vencida'],
  ] as const)(
    'estado terminal (%s): Editar deshabilitado, motivo en tooltip + nombre accesible, sin link',
    (status, label) => {
      renderActions({ status })
      const editar = screen.getByRole('button', { name: /editar/i })
      const reason = new RegExp(`no se puede editar una cotización ${label}`, 'i')
      expect(editar).toBeDisabled()
      // Y `aria-disabled`, que es el atributo que la mudanza al componente compartido pasó
      // del marcado al `...rest`. Sin esta línea, perderlo no rompía nada: la suite busca
      // por rol y nombre accesible, y ese camino no lo mira.
      expect(editar).toHaveAttribute('aria-disabled')
      // Tooltip propio (inmediato) con el motivo — reemplaza al `title` nativo (lento).
      expect(screen.getByText(reason)).toBeInTheDocument()
      // El motivo también viaja en el nombre accesible.
      expect(editar).toHaveAccessibleName(reason)
      expect(screen.queryByRole('link', { name: /editar/i })).not.toBeInTheDocument()
    },
  )

  it('descarga el PDF (object URL + click de <a>) al hacer click en Descargar', async () => {
    server.use(quotationPdf())
    const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    renderActions()

    await userEvent.click(screen.getByRole('button', { name: /descargar pdf/i }))

    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled())
    expect(anchorClick).toHaveBeenCalled()
  })

  it('abre el PDF en una pestaña nueva al Previsualizar', async () => {
    server.use(quotationPdf())
    const open = vi.spyOn(window, 'open').mockReturnValue({} as Window)
    renderActions()

    await userEvent.click(screen.getByRole('button', { name: /previsualizar pdf/i }))

    await waitFor(() => expect(open).toHaveBeenCalledWith('blob:mock-url', '_blank'))
  })

  it('avisa si el navegador bloquea la pestaña de previsualización', async () => {
    server.use(quotationPdf())
    vi.spyOn(window, 'open').mockReturnValue(null) // pop-up bloqueado
    renderActions()

    await userEvent.click(screen.getByRole('button', { name: /previsualizar pdf/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/bloqueador de pop-ups/i)
  })

  it('pide preview=true al Previsualizar y preview=false al Descargar', async () => {
    const sink: { preview?: string | null; cacheControl?: string | null } = {}
    server.use(quotationPdf(sink))
    vi.spyOn(window, 'open').mockReturnValue({} as Window)
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    renderActions()

    await userEvent.click(screen.getByRole('button', { name: /previsualizar pdf/i }))
    await waitFor(() => expect(sink.preview).toBe('true'))
    // Header de REQUEST: fuerza la revalidación del ETag en el browser.
    expect(sink.cacheControl).toBe('no-cache')

    await userEvent.click(screen.getByRole('button', { name: /descargar pdf/i }))
    await waitFor(() => expect(sink.preview).toBe('false'))
  })

  it('muestra el Problem.detail del backend si el PDF falla', async () => {
    server.use(quotationPdfError(500, { detail: 'No se pudo generar el PDF de la cotización 2026-00001' }))
    renderActions()

    await userEvent.click(screen.getByRole('button', { name: /descargar pdf/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'No se pudo generar el PDF de la cotización 2026-00001',
    )
  })

  it('deshabilita ambos botones de PDF mientras genera, y los dos se ven apagados', async () => {
    server.use(quotationPdfSlow(50))
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    renderActions()

    await userEvent.click(screen.getByRole('button', { name: /descargar pdf/i }))

    await waitFor(() => expect(screen.getByRole('button', { name: /descargar pdf/i })).toBeDisabled())
    expect(screen.getByRole('button', { name: /previsualizar pdf/i })).toBeDisabled()
    // Y se VE deshabilitado: sin esto, quitarles la clase de opacidad dejaba los botones
    // apagados solo para el DOM y encendidos para el ojo, con la suite en verde.
    for (const nombre of [/descargar pdf/i, /previsualizar pdf/i]) {
      expect(screen.getByRole('button', { name: nombre }).className).toContain(
        'disabled:opacity-60',
      )
    }
    await waitFor(() => expect(screen.getByRole('button', { name: /descargar pdf/i })).toBeEnabled())
  })

  // ----- Transiciones de estado -----
  it('DRAFT: muestra "Enviada" y dispara el PATCH {status:SENT} con If-Match', async () => {
    const sink: { body?: ChangeStatusBody; ifMatch?: string | null } = {}
    server.use(changeQuotationStatusSuccess(sink))
    renderActions({ status: 'DRAFT', etag: '"v9"' })

    await userEvent.click(screen.getByRole('button', { name: /enviada/i }))

    await waitFor(() => expect(sink.body?.status).toBe('SENT'))
    expect(sink.ifMatch).toBe('"v9"')
  })

  it('SENT: "Aceptada" dispara el PATCH {status:ACCEPTED}', async () => {
    const sink: { body?: ChangeStatusBody } = {}
    server.use(changeQuotationStatusSuccess(sink))
    renderActions({ status: 'SENT' })

    await userEvent.click(screen.getByRole('button', { name: /aceptada/i }))

    await waitFor(() => expect(sink.body?.status).toBe('ACCEPTED'))
  })

  it('SENT: "Rechazada" abre el modal y NO dispara el PATCH directo', async () => {
    const sink: { body?: ChangeStatusBody } = {}
    server.use(changeQuotationStatusSuccess(sink))
    renderActions({ status: 'SENT' })

    await userEvent.click(screen.getByRole('button', { name: /^rechazada$/i }))

    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(screen.getByLabelText(/motivo del rechazo/i)).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('estado terminal (REJECTED): no muestra botones de transición', () => {
    renderActions({ status: 'REJECTED' })
    expect(screen.queryByRole('button', { name: /^enviada$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /aceptada/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^rechazada$/i })).not.toBeInTheDocument()
  })

  it('oculta los botones de transición para dispatcher', () => {
    renderActions({ status: 'DRAFT', role: 'dispatcher' })
    expect(screen.queryByRole('button', { name: /enviada/i })).not.toBeInTheDocument()
    // Las acciones de PDF y editar siguen disponibles.
    expect(screen.getByRole('button', { name: /descargar pdf/i })).toBeInTheDocument()
  })

  it('muestra spinner y deshabilita Enviada mientras la transición está en vuelo', async () => {
    server.use(changeQuotationStatusSlow(50))
    renderActions({ status: 'DRAFT' })

    await userEvent.click(screen.getByRole('button', { name: /enviada/i }))

    await waitFor(() => expect(screen.getByRole('button', { name: /enviada/i })).toBeDisabled())
  })

  // ----- Errores de transición -----
  it('409 (transición inválida): toast con el detail + refetch', async () => {
    const onRefetch = vi.fn()
    const toastError = vi.spyOn(toast, 'error')
    server.use(changeQuotationStatusError(409, { code: 'QUO-005', detail: 'Transición no permitida.' }))
    renderActions({ status: 'DRAFT', onRefetch })

    await userEvent.click(screen.getByRole('button', { name: /enviada/i }))

    await waitFor(() => expect(toastError).toHaveBeenCalledWith('Transición no permitida.'))
    expect(onRefetch).toHaveBeenCalled()
  })

  it('412 (ETag stale): banner persistente + Recargar, sin toast', async () => {
    const onRefetch = vi.fn()
    const toastError = vi.spyOn(toast, 'error')
    server.use(changeQuotationStatusError(412, { code: 'COM-004', detail: 'Conflicto de versión.' }))
    renderActions({ status: 'DRAFT', onRefetch })

    await userEvent.click(screen.getByRole('button', { name: /enviada/i }))

    const banner = await screen.findByRole('alert')
    expect(banner).toHaveTextContent(/modificada por otra persona/i)
    expect(toastError).not.toHaveBeenCalled()

    await userEvent.click(within(banner).getByRole('button', { name: /recargar/i }))
    expect(onRefetch).toHaveBeenCalled()
  })

  it('403 (sin permiso): toast con el detail', async () => {
    const toastError = vi.spyOn(toast, 'error')
    server.use(changeQuotationStatusError(403, { detail: 'No tenés permiso.' }))
    renderActions({ status: 'DRAFT' })

    await userEvent.click(screen.getByRole('button', { name: /enviada/i }))

    await waitFor(() => expect(toastError).toHaveBeenCalledWith('No tenés permiso.'))
  })

  it('404 (no encontrada): toast con el detail', async () => {
    const toastError = vi.spyOn(toast, 'error')
    server.use(changeQuotationStatusError(404, { detail: 'No existe.' }))
    renderActions({ status: 'DRAFT' })

    await userEvent.click(screen.getByRole('button', { name: /enviada/i }))

    await waitFor(() => expect(toastError).toHaveBeenCalledWith('No existe.'))
  })
})
