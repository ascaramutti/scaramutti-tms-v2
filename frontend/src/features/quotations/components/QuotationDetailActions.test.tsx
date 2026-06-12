import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { QuotationDetailActions } from './QuotationDetailActions'
import { server } from '../../../test/mocks/server'
import { quotationPdf, quotationPdfError, quotationPdfSlow } from '../../../test/mocks/handlers/quotations'

function renderActions(props = { quotationId: 1, quotationCode: '2026-00001' }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <QuotationDetailActions {...props} />
      </MemoryRouter>
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

  it('renderiza los botones Previsualizar y Descargar', () => {
    renderActions()
    expect(screen.getByRole('button', { name: /previsualizar pdf/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /descargar pdf/i })).toBeInTheDocument()
  })

  it('muestra el botón Editar enlazando al wizard de edición', () => {
    renderActions({ quotationId: 7, quotationCode: '2026-00007' })
    expect(screen.getByRole('link', { name: /editar/i })).toHaveAttribute('href', '/cotizaciones/7/editar')
  })

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
    // Header de REQUEST: fuerza la revalidación del ETag en el browser (sin él,
    // el preview servía PDFs viejos desde el cache tras editar la cotización).
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

  it('deshabilita ambos botones mientras genera el PDF', async () => {
    server.use(quotationPdfSlow(50))
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    renderActions()

    await userEvent.click(screen.getByRole('button', { name: /descargar pdf/i }))

    await waitFor(() => expect(screen.getByRole('button', { name: /descargar pdf/i })).toBeDisabled())
    expect(screen.getByRole('button', { name: /previsualizar pdf/i })).toBeDisabled()
    await waitFor(() => expect(screen.getByRole('button', { name: /descargar pdf/i })).toBeEnabled())
  })
})
