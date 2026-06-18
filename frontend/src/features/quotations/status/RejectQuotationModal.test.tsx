import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { RejectQuotationModal } from './RejectQuotationModal'
import { server } from '../../../test/mocks/server'
import {
  changeQuotationStatusError,
  changeQuotationStatusSuccess,
} from '../../../test/mocks/handlers/quotations'
import type { ChangeStatusBody } from '../../../test/mocks/handlers/quotations'

interface Props {
  etag?: string | null
  onClose?: () => void
  onStatusError?: (error: unknown) => void
}

function renderModal({ etag = '"v1"', onClose, onStatusError }: Props = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <RejectQuotationModal
        isOpen
        quotationId={1}
        etag={etag}
        onClose={onClose ?? (() => {})}
        onStatusError={onStatusError}
      />
    </QueryClientProvider>,
  )
}

const REASON_LABEL = /motivo del rechazo/i

describe('RejectQuotationModal', () => {
  // ----- Validación -----
  it('motivo vacío → error inline y NO dispara el PATCH', async () => {
    const sink: { body?: ChangeStatusBody } = {}
    server.use(changeQuotationStatusSuccess(sink))
    renderModal()

    await userEvent.click(screen.getByRole('button', { name: /registrar rechazo/i }))

    expect(await screen.findByText(/el motivo del rechazo es obligatorio/i)).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('motivo de solo espacios → error inline (se trimea a vacío)', async () => {
    const sink: { body?: ChangeStatusBody } = {}
    server.use(changeQuotationStatusSuccess(sink))
    renderModal()

    await userEvent.type(screen.getByLabelText(REASON_LABEL), '    ')
    await userEvent.click(screen.getByRole('button', { name: /registrar rechazo/i }))

    expect(await screen.findByText(/obligatorio/i)).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('el textarea limita la entrada a 500 caracteres (maxLength)', () => {
    renderModal()
    expect(screen.getByLabelText(REASON_LABEL)).toHaveAttribute('maxlength', '500')
  })

  it('limpia los caracteres de control al pegar (sanitize L2), conservando espacios', async () => {
    const sink: { body?: ChangeStatusBody } = {}
    server.use(changeQuotationStatusSuccess(sink))
    renderModal()

    const textarea = screen.getByLabelText(REASON_LABEL)
    // Control-chars (NUL + BEL) entre palabras: deben desaparecer, pero el espacio normal se
    // conserva. Se construyen en runtime para no meter bytes de control al archivo fuente.
    const NUL = String.fromCharCode(0)
    const BEL = String.fromCharCode(7)
    await userEvent.click(textarea)
    await userEvent.paste(`mal${NUL} te${BEL}xto`)
    await userEvent.click(screen.getByRole('button', { name: /registrar rechazo/i }))

    await waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body?.rejectionReason).toBe('mal texto')
  })

  // ----- Submit OK -----
  it('motivo válido → PATCH { status: REJECTED, rejectionReason } + If-Match → toast + cierra', async () => {
    const sink: { body?: ChangeStatusBody; ifMatch?: string | null } = {}
    server.use(changeQuotationStatusSuccess(sink))
    const onClose = vi.fn()
    const toastSuccess = vi.spyOn(toast, 'success')
    renderModal({ etag: '"v7"', onClose })

    await userEvent.type(screen.getByLabelText(REASON_LABEL), 'Precio fuera de presupuesto')
    await userEvent.click(screen.getByRole('button', { name: /registrar rechazo/i }))

    await waitFor(() =>
      expect(sink.body).toEqual({ status: 'REJECTED', rejectionReason: 'Precio fuera de presupuesto' }),
    )
    expect(sink.ifMatch).toBe('"v7"')
    expect(toastSuccess).toHaveBeenCalledWith('Cotización rechazada.')
    expect(onClose).toHaveBeenCalled()
  })

  it('muestra el aviso de uso interno (no PDF, no cliente)', () => {
    renderModal()
    expect(screen.getByText(/no aparece en el pdf ni se envía al cliente/i)).toBeInTheDocument()
    // "interno" aparece en el helper y en el badge; basta con que esté presente.
    expect(screen.getAllByText(/interno/i).length).toBeGreaterThan(0)
  })

  // ----- A11y -----
  it('es un dialog accesible: role, aria-modal y etiquetado por el título', () => {
    renderModal()
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByRole('heading', { name: /rechazo de la cotización/i })).toBeInTheDocument()
  })

  it('pone el foco inicial en el textarea del motivo', async () => {
    renderModal()
    await waitFor(() => expect(screen.getByLabelText(REASON_LABEL)).toHaveFocus())
  })

  it('Escape cierra el modal', async () => {
    const onClose = vi.fn()
    renderModal({ onClose })
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
  })

  // ----- Errores del backend -----
  it('400 (motivo inválido) → error inline en el campo (no delega al padre)', async () => {
    const onStatusError = vi.fn()
    server.use(
      changeQuotationStatusError(400, {
        code: 'COM-001',
        detail: 'Datos inválidos.',
        errors: [{ field: 'rejectionReason', message: 'Motivo inválido.' }],
      }),
    )
    renderModal({ onStatusError })

    await userEvent.type(screen.getByLabelText(REASON_LABEL), 'algo')
    await userEvent.click(screen.getByRole('button', { name: /registrar rechazo/i }))

    expect(await screen.findByText('Motivo inválido.')).toBeInTheDocument()
    expect(onStatusError).not.toHaveBeenCalled()
  })

  it('400 SIN errors[] (COM-001 a secas) → detail inline en el textarea, no toast', async () => {
    const onStatusError = vi.fn()
    const toastError = vi.spyOn(toast, 'error')
    server.use(
      changeQuotationStatusError(400, { code: 'COM-001', detail: 'El motivo del rechazo es obligatorio.' }),
    )
    renderModal({ onStatusError })

    await userEvent.type(screen.getByLabelText(REASON_LABEL), 'algo')
    await userEvent.click(screen.getByRole('button', { name: /registrar rechazo/i }))

    // El `detail` del backend aparece inline, como error del campo (role="alert" del Textarea),
    // y el textarea queda marcado inválido (aria-invalid) → no es un toast genérico.
    const inlineError = await screen.findByRole('alert')
    expect(inlineError).toHaveTextContent('El motivo del rechazo es obligatorio.')
    expect(screen.getByLabelText(REASON_LABEL)).toHaveAttribute('aria-invalid', 'true')
    // No se delega al padre ni se dispara un toast genérico.
    expect(onStatusError).not.toHaveBeenCalled()
    expect(toastError).not.toHaveBeenCalled()
  })

  it('409 (transición inválida) → delega al padre (onStatusError)', async () => {
    const onStatusError = vi.fn()
    server.use(changeQuotationStatusError(409, { code: 'QUO-005', detail: 'Transición inválida.' }))
    renderModal({ onStatusError })

    await userEvent.type(screen.getByLabelText(REASON_LABEL), 'motivo')
    await userEvent.click(screen.getByRole('button', { name: /registrar rechazo/i }))

    await waitFor(() => expect(onStatusError).toHaveBeenCalled())
  })

  it('412 (ETag stale) → delega al padre (no muestra error inline)', async () => {
    const onStatusError = vi.fn()
    server.use(changeQuotationStatusError(412, { code: 'COM-004', detail: 'Conflicto de versión.' }))
    renderModal({ onStatusError })

    await userEvent.type(screen.getByLabelText(REASON_LABEL), 'motivo')
    await userEvent.click(screen.getByRole('button', { name: /registrar rechazo/i }))

    await waitFor(() => expect(onStatusError).toHaveBeenCalled())
    expect(screen.queryByText(/conflicto de versión/i)).not.toBeInTheDocument()
  })
})
