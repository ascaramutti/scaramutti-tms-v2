import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { CargoTypeField } from './CargoTypeField'
import { server } from '../../../test/mocks/server'
import {
  cargoTypesSearch,
  fakeCargoType,
  pageOfCargoTypes,
} from '../../../test/mocks/handlers/cargotypes'

const API = 'http://localhost:8080/api/v1'

interface RenderOptions {
  value?: number | null
  valueName?: string
  error?: string
  canCreate?: boolean
  onBlur?: () => void
}

function renderField({ value = null, valueName, error, canCreate, onBlur }: RenderOptions = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onChange = vi.fn()
  render(
    <QueryClientProvider client={queryClient}>
      <CargoTypeField
        id="cargo"
        value={value}
        valueName={valueName}
        onChange={onChange}
        onBlur={onBlur}
        error={error}
        canCreate={canCreate}
      />
    </QueryClientProvider>,
  )
  return { onChange }
}

describe('CargoTypeField', () => {
  it('entrega el tipo de carga ELEGIDO cuando hay varios candidatos', async () => {
    const user = userEvent.setup()
    // Con una lista de un solo elemento, un buscador que devolviera siempre el
    // primero sería indistinguible del correcto. Con tres, no.
    const segundo = fakeCargoType({ id: 8, name: 'CONTENEDOR 40', standardWeight: 3800 })
    server.use(
      cargoTypesSearch([
        fakeCargoType({ id: 7, name: 'CARGA GENERAL', standardWeight: 1000 }),
        segundo,
        fakeCargoType({ id: 9, name: 'CARGA PELIGROSA', standardWeight: 2000 }),
      ]),
    )
    const { onChange } = renderField()

    await user.type(screen.getByLabelText('Tipo de carga'), 'car')
    await user.click(await screen.findByText('CONTENEDOR 40'))

    expect(onChange).toHaveBeenCalledWith(segundo)
  })

  it('entrega el objeto completo, no solo el id, para poder precargar sus medidas', async () => {
    const user = userEvent.setup()
    const conMedidas = fakeCargoType({
      id: 7,
      name: 'CARGA GENERAL',
      standardWeight: 28000,
      standardLength: 12.5,
    })
    server.use(cargoTypesSearch([conMedidas]))
    const { onChange } = renderField()

    await user.type(screen.getByLabelText('Tipo de carga'), 'carga')
    await user.click(await screen.findByText('CARGA GENERAL'))

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ standardWeight: 28000, standardLength: 12.5 }),
    )
  })

  it('no busca con menos de 3 caracteres y lo explica', async () => {
    const user = userEvent.setup()
    const consultas: string[] = []
    server.use(
      http.get(`${API}/cargo-types`, ({ request }) => {
        consultas.push(new URL(request.url).searchParams.get('q') ?? '')
        return HttpResponse.json(pageOfCargoTypes([fakeCargoType()]))
      }),
    )
    renderField()

    await user.type(screen.getByLabelText('Tipo de carga'), 'ca')
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
      http.get(`${API}/cargo-types`, ({ request }) => {
        consultas.push(new URL(request.url).searchParams.get('q') ?? '')
        return HttpResponse.json(pageOfCargoTypes([fakeCargoType()]))
      }),
    )
    renderField()

    await user.type(screen.getByLabelText('Tipo de carga'), 'car')
    await screen.findByText('CARGA GENERAL')
    expect(consultas).toContain('car')
  })

  it('muestra el nombre que recibe, sin volver a buscarlo', () => {
    // El componente no guarda el nombre del elegido: lo recibe. Así sobrevive a un
    // remonte sin repetir la consulta.
    renderField({ value: 7, valueName: 'CARGA GENERAL' })
    expect(screen.getByText('CARGA GENERAL')).toBeInTheDocument()
  })

  it('muestra el error que le pasan', () => {
    renderField({ error: 'Selecciona el tipo de carga' })
    expect(screen.getByText('Selecciona el tipo de carga')).toBeInTheDocument()
  })

  it('avisa cuando el campo pierde el foco, para que el formulario valide', async () => {
    const user = userEvent.setup()
    const onBlur = vi.fn()
    renderField({ onBlur })

    await user.click(screen.getByLabelText('Tipo de carga'))
    await user.tab()

    expect(onBlur).toHaveBeenCalled()
  })

  it('ofrece el alta al vuelo por omisión', async () => {
    const user = userEvent.setup()
    renderField()
    await user.type(screen.getByLabelText('Tipo de carga'), 'nuevo')
    expect(await screen.findByText('Nuevo tipo de carga')).toBeInTheDocument()
  })

  it('esconde el alta al vuelo a quien no puede crear, sin sacarle el buscador', async () => {
    const user = userEvent.setup()
    server.use(cargoTypesSearch([fakeCargoType({ name: 'CARGA GENERAL' })]))
    renderField({ canCreate: false })

    await user.type(screen.getByLabelText('Tipo de carga'), 'carga')

    // El buscador sigue trayendo resultados: lo que se saca es el atajo de alta.
    expect(await screen.findByText('CARGA GENERAL')).toBeInTheDocument()
    expect(screen.queryByText('Nuevo tipo de carga')).not.toBeInTheDocument()
  })

  it('el creado al vuelo queda elegido', async () => {
    const user = userEvent.setup()
    const creado = fakeCargoType({ id: 99, name: 'NUEVA CARGA', standardWeight: 500 })
    server.use(http.post(`${API}/cargo-types`, () => HttpResponse.json(creado, { status: 201 })))
    const { onChange } = renderField()

    await user.type(screen.getByLabelText('Tipo de carga'), 'nueva')
    await user.click(await screen.findByText('Nuevo tipo de carga'))
    await user.clear(screen.getByLabelText('Nombre'))
    await user.type(screen.getByLabelText('Nombre'), 'NUEVA CARGA')
    await user.type(screen.getByLabelText(/peso estándar/i), '500')
    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    await vi.waitFor(() => expect(onChange).toHaveBeenCalledWith(creado))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('limpiar la selección la borra en el formulario', async () => {
    const user = userEvent.setup()
    const { onChange } = renderField({ value: 7, valueName: 'CARGA GENERAL' })

    await user.click(screen.getByRole('button', { name: /quitar selección/i }))

    expect(onChange).toHaveBeenCalledWith(null)
  })
})

describe('CargoTypeField, dos en la misma pantalla', () => {
  it('cada campo usa el id que le pasan, así el rótulo apunta al suyo', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    // Con un id fijo adentro del componente, los dos labels apuntarían al mismo
    // input y hacer clic en el segundo rótulo movería el foco al primero.
    render(
      <QueryClientProvider client={queryClient}>
        <CargoTypeField id="cargo-uno" value={null} onChange={vi.fn()} />
        <CargoTypeField id="cargo-dos" value={null} onChange={vi.fn()} />
      </QueryClientProvider>,
    )

    const campos = screen.getAllByLabelText('Tipo de carga')
    expect(campos).toHaveLength(2)
    expect(campos[0].id).toBe('cargo-uno')
    expect(campos[1].id).toBe('cargo-dos')
  })
})
