import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { QuotationStatusActions } from './QuotationStatusActions'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import {
  changeQuotationStatusError,
  changeQuotationStatusSlow,
  changeQuotationStatusSuccess,
} from '../../../test/mocks/handlers/quotations'
import type { ChangeStatusBody } from '../../../test/mocks/handlers/quotations'
import type { QuotationStatus, UserRole } from '../../../api'

interface Props {
  status?: QuotationStatus
  etag?: string | null
  role?: UserRole
  onRejectClick?: () => void
  onStatusError?: (error: unknown) => void
}

function renderActions({
  status = 'DRAFT',
  etag = '"v1"',
  role = 'admin',
  onRejectClick,
  onStatusError,
}: Props = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, { ...fakeUser, role })
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <QuotationStatusActions
          quotationId={1}
          status={status}
          etag={etag}
          onRejectClick={onRejectClick}
          onStatusError={onStatusError}
        />
      </AuthProvider>
    </QueryClientProvider>,
  )
}

describe('QuotationStatusActions', () => {
  // ----- Qué botones se muestran -----
  it('DRAFT muestra solo "Enviada"', () => {
    renderActions({ status: 'DRAFT' })
    expect(screen.getByRole('button', { name: /enviada/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /aceptada/i })).not.toBeInTheDocument()
  })

  it('SENT muestra "Aceptada" y "Rechazada"', () => {
    renderActions({ status: 'SENT' })
    expect(screen.getByRole('button', { name: /aceptada/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^rechazada$/i })).toBeInTheDocument()
  })

  it('estados terminales no renderizan nada', () => {
    const { container } = renderActions({ status: 'ACCEPTED' })
    expect(container).toBeEmptyDOMElement()
  })

  it('oculto para dispatcher (rol sin permiso)', () => {
    const { container } = renderActions({ status: 'DRAFT', role: 'dispatcher' })
    expect(container).toBeEmptyDOMElement()
  })

  // ----- Ejecuta la transición -----
  it('click "Enviada" → PATCH { status: SENT } con If-Match', async () => {
    const sink: { body?: ChangeStatusBody; ifMatch?: string | null } = {}
    server.use(changeQuotationStatusSuccess(sink))
    renderActions({ status: 'DRAFT', etag: '"v42"' })

    await userEvent.click(screen.getByRole('button', { name: /enviada/i }))

    await waitFor(() => expect(sink.body?.status).toBe('SENT'))
    expect(sink.ifMatch).toBe('"v42"')
  })

  it('click "Aceptada" → PATCH { status: ACCEPTED }', async () => {
    const sink: { body?: ChangeStatusBody } = {}
    server.use(changeQuotationStatusSuccess(sink))
    renderActions({ status: 'SENT' })

    await userEvent.click(screen.getByRole('button', { name: /aceptada/i }))

    await waitFor(() => expect(sink.body?.status).toBe('ACCEPTED'))
  })

  it('click "Rechazada" → NO dispara PATCH, llama onRejectClick', async () => {
    const sink: { body?: ChangeStatusBody } = {}
    server.use(changeQuotationStatusSuccess(sink))
    const onRejectClick = vi.fn()
    renderActions({ status: 'SENT', onRejectClick })

    await userEvent.click(screen.getByRole('button', { name: /^rechazada$/i }))

    expect(onRejectClick).toHaveBeenCalledTimes(1)
    expect(sink.body).toBeUndefined()
  })

  it('spinner + disabled mientras la transición está en vuelo', async () => {
    server.use(changeQuotationStatusSlow(50))
    renderActions({ status: 'DRAFT' })

    await userEvent.click(screen.getByRole('button', { name: /enviada/i }))

    await waitFor(() => expect(screen.getByRole('button', { name: /enviada/i })).toBeDisabled())
  })

  it('reporta el error de la transición al padre (onStatusError)', async () => {
    server.use(changeQuotationStatusError(409, { detail: 'Transición inválida.' }))
    const onStatusError = vi.fn()
    renderActions({ status: 'DRAFT', onStatusError })

    await userEvent.click(screen.getByRole('button', { name: /enviada/i }))

    await waitFor(() => expect(onStatusError).toHaveBeenCalled())
  })
})
