import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { ServiceClientField } from './ServiceClientField'
import type { ClientResponse } from '../../../api'
import { server } from '../../../test/mocks/server'
import { clientsSearch, fakeClient, pageOfClients } from '../../../test/mocks/handlers/clients'

const API = 'http://localhost:8080/api/v1'

interface RenderOptions {
  value?: ClientResponse | null
  error?: string
  canCreate?: boolean
  onBlur?: () => void
}

function renderField({ value = null, error, canCreate, onBlur }: RenderOptions = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onChange = vi.fn()
  render(
    <QueryClientProvider client={queryClient}>
      <ServiceClientField
        value={value}
        onChange={onChange}
        onBlur={onBlur}
        error={error}
        canCreate={canCreate}
      />
    </QueryClientProvider>,
  )
  return { onChange }
}

describe('ServiceClientField', () => {
  it('entrega el cliente ELEGIDO cuando hay varios candidatos', async () => {
    const user = userEvent.setup()
    // Es el caso de mayor consecuencia del formulario: con un solo candidato, un
    // buscador que devolviera siempre el primero se vería idéntico al correcto y el
    // viaje quedaría facturado a otra empresa.
    const elegido = fakeClient({ id: 12, name: 'ACME S.A.C.', ruc: '20123456789' })
    server.use(
      clientsSearch([
        fakeClient({ id: 5, name: 'ACME NORTE S.A.C.', ruc: '20100000001' }),
        elegido,
        fakeClient({ id: 31, name: 'ACME SUR S.A.C.', ruc: '20100000003' }),
      ]),
    )
    const { onChange } = renderField()

    await user.type(screen.getByLabelText('Cliente'), 'acme')
    await user.click(await screen.findByText('ACME S.A.C.'))

    expect(onChange).toHaveBeenCalledWith(elegido)
  })

  it('muestra el RUC del elegido en un campo que no se escribe', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ id: 12, name: 'ACME S.A.C.', ruc: '20123456789' })]))
    const { onChange } = renderField()

    await user.type(screen.getByLabelText('Cliente'), 'acme')
    await user.click(await screen.findByText('ACME S.A.C.'))

    expect(onChange).toHaveBeenCalled()
    const ruc = screen.getByLabelText('RUC del cliente seleccionado') as HTMLInputElement
    expect(ruc).toHaveAttribute('readonly')
  })

  it('parte con el RUC vacío mientras no haya cliente', () => {
    renderField()
    expect((screen.getByLabelText('RUC del cliente seleccionado') as HTMLInputElement).value).toBe('')
  })

  it('muestra el RUC del cliente que recibe ya elegido', () => {
    renderField({ value: fakeClient({ id: 12, name: 'ACME S.A.C.', ruc: '20123456789' }) })
    expect((screen.getByLabelText('RUC del cliente seleccionado') as HTMLInputElement).value).toBe(
      '20123456789',
    )
  })

  it('no busca con menos de 3 caracteres y lo explica', async () => {
    const user = userEvent.setup()
    const consultas: string[] = []
    server.use(
      http.get(`${API}/clients`, ({ request }) => {
        consultas.push(new URL(request.url).searchParams.get('q') ?? '')
        return HttpResponse.json(pageOfClients([fakeClient()]))
      }),
    )
    renderField()

    await user.type(screen.getByLabelText('Cliente'), 'ac')
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    // Espera mayor al debounce: sin esto, "no se disparó" es indistinguible de
    // "todavía no se disparó".
    await new Promise((resolve) => setTimeout(resolve, 450))
    expect(consultas).toEqual([])
  })

  it('busca a partir del tercer caracter', async () => {
    const user = userEvent.setup()
    const consultas: string[] = []
    server.use(
      http.get(`${API}/clients`, ({ request }) => {
        consultas.push(new URL(request.url).searchParams.get('q') ?? '')
        return HttpResponse.json(pageOfClients([fakeClient({ name: 'ACME S.A.C.' })]))
      }),
    )
    renderField()

    await user.type(screen.getByLabelText('Cliente'), 'acm')
    await screen.findByText('ACME S.A.C.')
    expect(consultas).toContain('acm')
  })

  it('muestra el error que le pasan', () => {
    renderField({ error: 'Selecciona el cliente' })
    expect(screen.getByText('Selecciona el cliente')).toBeInTheDocument()
  })

  it('avisa cuando el campo pierde el foco, para que el formulario valide', async () => {
    const user = userEvent.setup()
    const onBlur = vi.fn()
    renderField({ onBlur })

    await user.click(screen.getByLabelText('Cliente'))
    await user.tab()

    expect(onBlur).toHaveBeenCalled()
  })

  it('limpiar la selección la borra en el formulario', async () => {
    const user = userEvent.setup()
    const { onChange } = renderField({ value: fakeClient({ id: 12, name: 'ACME S.A.C.' }) })

    await user.click(screen.getByRole('button', { name: /quitar selección/i }))

    expect(onChange).toHaveBeenCalledWith(null)
  })

  // ----- El alta al vuelo y su reja de rol -----
  it('no ofrece el alta al vuelo si no se la habilitan', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ name: 'ACME S.A.C.' })]))
    renderField()

    await user.type(screen.getByLabelText('Cliente'), 'acme')

    // El default es no ofrecerla: el componente nace nuevo, así que olvidar la prop
    // no puede terminar mostrándole un atajo a quien el servidor le responde 403.
    expect(await screen.findByText('ACME S.A.C.')).toBeInTheDocument()
    expect(screen.queryByText('Nuevo cliente')).not.toBeInTheDocument()
  })

  it('la esconde a quien no puede crear, sin sacarle el buscador', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ name: 'ACME S.A.C.' })]))
    renderField({ canCreate: false })

    await user.type(screen.getByLabelText('Cliente'), 'acme')

    expect(await screen.findByText('ACME S.A.C.')).toBeInTheDocument()
    expect(screen.queryByText('Nuevo cliente')).not.toBeInTheDocument()
  })

  it('la ofrece a quien sí puede', async () => {
    const user = userEvent.setup()
    renderField({ canCreate: true })
    await user.type(screen.getByLabelText('Cliente'), 'nueva')
    expect(await screen.findByText('Nuevo cliente')).toBeInTheDocument()
  })

  it('el cliente creado al vuelo queda elegido', async () => {
    const user = userEvent.setup()
    const creado = fakeClient({ id: 99, name: 'NUEVA SAC', ruc: '20111111111' })
    server.use(http.post(`${API}/clients`, () => HttpResponse.json(creado, { status: 201 })))
    const { onChange } = renderField({ canCreate: true })

    await user.type(screen.getByLabelText('Cliente'), 'nueva')
    await user.click(await screen.findByText('Nuevo cliente'))
    await user.clear(screen.getByLabelText('Razón social'))
    await user.type(screen.getByLabelText('Razón social'), 'NUEVA SAC')
    // El RUC del modal, no el de solo lectura del campo: los dos se llaman igual.
    await user.type(within(screen.getByRole('dialog')).getByLabelText('RUC'), '20111111111')
    await user.click(screen.getByRole('button', { name: /crear cliente/i }))

    await vi.waitFor(() => expect(onChange).toHaveBeenCalledWith(creado))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
