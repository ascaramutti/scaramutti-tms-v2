import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { ClientsSearchPage } from './ClientsSearchPage'
import { CLIENTS_BASE } from '../../../shared/paths'
import { server } from '../../../test/mocks/server'
import {
  clientsCapture,
  clientsSearch,
  clientsSearchError,
  clientsSearchPartial,
  clientsSearchSlow,
  fakeClient,
} from '../../../test/mocks/handlers/clients'

/** Más que el rebote de 300 ms: separa "no se disparó" de "todavía no se disparó". */
const DESPUES_DEL_REBOTE = 450

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[CLIENTS_BASE]}>
        <ClientsSearchPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function esperarElRebote() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, DESPUES_DEL_REBOTE))
  })
}

describe('ClientsSearchPage', () => {
  it('al abrir no busca ni muestra el vacío', async () => {
    const sink: { params?: URLSearchParams } = {}
    server.use(clientsCapture(sink, [fakeClient()]))
    renderPage()

    await esperarElRebote()

    expect(sink.params).toBeUndefined()
    expect(screen.queryByText('No encontramos clientes con ese texto')).not.toBeInTheDocument()
  })

  /**
   * El handler devuelve UN cliente a propósito: con la lista vacía, "no se ve
   * ningún resultado" sería una coartada del código malo, que podría haber
   * buscado igual.
   */
  it('con menos de 3 caracteres no llama al backend', async () => {
    const user = userEvent.setup()
    const sink: { params?: URLSearchParams } = {}
    server.use(clientsCapture(sink, [fakeClient()]))
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'ac')
    await esperarElRebote()

    expect(sink.params).toBeUndefined()
  })

  it('busca a partir del tercer caracter', async () => {
    const user = userEvent.setup()
    const sink: { params?: URLSearchParams } = {}
    server.use(clientsCapture(sink, [fakeClient()]))
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acm')

    // Se espera a que la pantalla muestre el resultado, no a que el doble reciba
    // la consulta: lo segundo se cumple antes de que la respuesta llegue.
    await screen.findByText('ACME S.A.C.')
    expect(sink.params?.get('q')).toBe('acm')
  })

  /**
   * `toBeTruthy` no serviría: la cadena "false" también lo es, así que
   * sobreviviría a mandar el filtro al revés.
   *
   * Lo que este caso NO mide es que un inactivo no aparezca: eso lo filtra el
   * backend. La red del frontend termina en el parámetro.
   */
  it('la búsqueda pide solo clientes activos', async () => {
    const user = userEvent.setup()
    const sink: { params?: URLSearchParams } = {}
    server.use(clientsCapture(sink, [fakeClient()]))
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')

    await screen.findByText('ACME S.A.C.')
    expect(sink.params?.get('isActive')).toBe('true')
  })

  it('muestra el estado de carga mientras busca', async () => {
    const user = userEvent.setup()
    server.use(clientsSearchSlow([fakeClient()], 60))
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')

    await waitFor(() => expect(screen.getByLabelText('Buscando clientes')).toBeInTheDocument())
    expect(await screen.findByText('ACME S.A.C.')).toBeInTheDocument()
  })

  it('lista cada coincidencia con su razón social y su RUC', async () => {
    const user = userEvent.setup()
    server.use(
      clientsSearch([
        fakeClient({ id: 5, name: 'ACME NORTE S.A.C.', ruc: '20100000001' }),
        fakeClient({ id: 12, name: 'ACME S.A.C.', ruc: '20123456789' }),
      ]),
    )
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')

    const primera = (await screen.findByText('ACME NORTE S.A.C.')).closest('a')
    const segunda = screen.getByText('ACME S.A.C.').closest('a')
    expect(within(primera as HTMLElement).getByText(/20100000001/)).toBeInTheDocument()
    expect(within(segunda as HTMLElement).getByText(/20123456789/)).toBeInTheDocument()
  })

  /**
   * El id del fixture es 7 y no 1: con el índice de la fila el destino sería el
   * mismo si el único resultado tuviera id 1, y el caso pasaría con el código
   * malo.
   */
  it('cada resultado lleva al detalle de ese cliente', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ id: 7, name: 'ACME S.A.C.' })]))
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')

    expect(await screen.findByRole('link', { name: /ver ACME S\.A\.C\./i })).toHaveAttribute(
      'href',
      `${CLIENTS_BASE}/7`,
    )
  })

  it('sin coincidencias lo dice con el texto de la historia', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([]))
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'zzz')

    expect(await screen.findByText('No encontramos clientes con ese texto')).toBeInTheDocument()
  })

  /**
   * En esta pantalla no se da de alta: el alta sigue siendo al vuelo desde el
   * asistente. La doble negativa cubre las dos formas de ofrecerlo, botón o texto.
   */
  it('el vacío no ofrece crear un cliente', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([]))
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'zzz')
    await screen.findByText('No encontramos clientes con ese texto')

    expect(screen.queryByRole('button', { name: /nuevo cliente/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/crear cliente/i)).not.toBeInTheDocument()
  })

  /**
   * Una caída de red no es "ese cliente no existe". Sin este caso, tratar
   * cualquier error como vacío le diría al usuario que el cliente no está
   * cuando lo que se cayó es el servidor.
   */
  it('un error de búsqueda se explica y deja reintentar', async () => {
    const user = userEvent.setup()
    server.use(clientsSearchError())
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /reintentar/i })).toBeInTheDocument()
    expect(screen.queryByText('No encontramos clientes con ese texto')).not.toBeInTheDocument()
  })

  it('avisa cuándo hay más coincidencias que filas', async () => {
    const user = userEvent.setup()
    server.use(
      clientsSearchPartial([fakeClient({ id: 1, name: 'ACME UNO' })], 25),
    )
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')

    expect(
      await screen.findByText(/se muestran los primeros/i, { selector: 'p:not(.sr-only)' }),
    ).toBeInTheDocument()
  })

  it('no avisa de más coincidencias cuando están todas', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ id: 1, name: 'ACME UNO' })]))
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')
    await screen.findByText('ACME UNO')

    expect(
      screen.queryByText(/se muestran los primeros/i, { selector: 'p:not(.sr-only)' }),
    ).not.toBeInTheDocument()
  })

  it('la pista del mínimo está desde que se abre y desaparece al llegar al mínimo', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([]))
    renderPage()

    const input = screen.getByLabelText(/buscar cliente/i)
    expect(screen.getByText(/ingresa al menos 3 caracteres/i)).toBeInTheDocument()
    expect(input).toHaveAttribute('aria-describedby', 'q-hint')

    await user.type(input, 'acm')

    await waitFor(() =>
      expect(screen.queryByText(/ingresa al menos 3 caracteres/i)).not.toBeInTheDocument(),
    )
    // Y la referencia se va con ella: si queda colgada, el campo apunta a un
    // elemento que no existe y el lector de pantalla no lee nada.
    expect(input).not.toHaveAttribute('aria-describedby')
  })

  it('el estado vacío usa el componente compartido y no una caja a mano', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([]))
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'zzz')
    const vacio = await screen.findByText('No encontramos clientes con ese texto')

    // El ícono se busca DENTRO del vacío: la barra de búsqueda dibuja el suyo
    // siempre, así que buscarlo en toda la pantalla encuentra ese otro y el caso
    // pasa aunque el vacío no dibuje ninguno.
    const caja = vacio.parentElement as HTMLElement
    expect(caja.className).toContain('py-16')
    expect(caja.querySelector('svg[aria-hidden="true"]')).toBeTruthy()
  })

  it('no tiene violaciones de accesibilidad con resultados', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ id: 7 })]))
    const { container } = renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')
    await screen.findByText('ACME S.A.C.')

    expect(await axe(container)).toHaveNoViolations()
  })

  it('no tiene violaciones de accesibilidad con el estado vacío', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([]))
    const { container } = renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'zzz')
    await screen.findByText('No encontramos clientes con ese texto')

    expect(await axe(container)).toHaveNoViolations()
  })
  /**
   * El backend topea el término en 200 y con más devuelve un 400, que esta
   * pantalla mostraría como un error pasajero con un botón de reintentar que no
   * puede funcionar. El tope del campo es lo que evita llegar ahí.
   */
  it('el campo topea el término en el máximo que el backend acepta', async () => {
    server.use(clientsSearch([]))
    renderPage()

    expect(screen.getByLabelText(/buscar cliente/i)).toHaveAttribute('maxlength', '200')
  })

  /**
   * El tamaño de página lo comparten tres pantallas con intenciones distintas:
   * acá son las filas visibles del listado, en los dos combobox de alta al vuelo
   * son las opciones del desplegable. Los tres lo fijan para que moverlo no
   * arrastre a los otros dos en silencio.
   */
  it('pide diez filas por consulta', async () => {
    const user = userEvent.setup()
    const sink: { params?: URLSearchParams } = {}
    server.use(clientsCapture(sink, [fakeClient()]))
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')

    await screen.findByText('ACME S.A.C.')
    expect(sink.params?.get('size')).toBe('10')
  })

  /**
   * El botón de reintentar tiene que reintentar. Afirmar que está no distingue
   * un botón vivo de uno con el manejador vacío, que es lo que el usuario se
   * encuentra cuando la red vuelve y la pantalla no.
   */
  it('reintentar vuelve a buscar y muestra los resultados', async () => {
    const user = userEvent.setup()
    server.use(clientsSearchError())
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')
    await screen.findByRole('alert')

    server.use(clientsSearch([fakeClient({ id: 7, name: 'ACME S.A.C.' })]))
    await user.click(screen.getByRole('button', { name: /reintentar/i }))

    expect(await screen.findByText('ACME S.A.C.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  /**
   * El aviso de que hay más se afirma con los NÚMEROS: la frase sola pasa igual
   * con los dos invertidos, o sea diciendo que se muestran 25 de 1.
   */
  it('el aviso de más coincidencias dice cuántas se muestran y cuántas hay', async () => {
    const user = userEvent.setup()
    server.use(clientsSearchPartial([fakeClient({ id: 1, name: 'ACME UNO' })], 25))
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')

    expect(
      await screen.findByText(/se muestran los primeros 1 de 25/i, { selector: 'p:not(.sr-only)' }),
    ).toBeInTheDocument()
  })

  /**
   * El rebote existe para no disparar una consulta por tecla. Sin contar las
   * llamadas, escribir cuatro letras dispararía cuatro búsquedas y la suite
   * quedaría igual de verde.
   */
  it('escribir de corrido dispara una sola búsqueda', async () => {
    const user = userEvent.setup()
    const sink: { params?: URLSearchParams; llamadas?: number } = {}
    server.use(clientsCapture(sink, [fakeClient()]))
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')
    await esperarElRebote()

    expect(sink.llamadas).toBe(1)
  })

  /**
   * La región viva tiene que estar en el árbol ANTES de que llegue el texto: una
   * que se inserta junto con su contenido no la anuncia ningún lector de
   * pantalla, así que la primera búsqueda pasaría en silencio. Desde la segunda
   * funcionaría igual, que es lo que hace que el error sea difícil de ver.
   */
  it('la región viva ya está montada mientras se busca', async () => {
    const user = userEvent.setup()
    server.use(clientsSearchSlow([fakeClient()], 120))
    const { container } = renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')
    await waitFor(() => expect(screen.getByLabelText('Buscando clientes')).toBeInTheDocument())

    expect(container.querySelector('[aria-live="polite"]')).not.toBeNull()
  })

  /**
   * Y anuncia cuántas coincidencias hay, no cuántas filas se ven: con una fila de
   * veinticinco, decir "1 cliente" es peor que el silencio, porque es un número
   * falso y esconde la instrucción de afinar la búsqueda.
   */
  it('el anuncio dice las coincidencias y no las filas visibles', async () => {
    const user = userEvent.setup()
    server.use(clientsSearchPartial([fakeClient({ id: 1, name: 'ACME UNO' })], 25))
    const { container } = renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')
    await screen.findByText('ACME UNO')

    expect(container.querySelector('[aria-live="polite"]')).toHaveTextContent('25')
  })

  /** MI4: el mensaje del backend por delante del genérico, también acá. */
  it('un error de búsqueda muestra lo que explica el backend', async () => {
    const user = userEvent.setup()
    server.use(clientsSearchError())
    renderPage()

    await user.type(screen.getByLabelText(/buscar cliente/i), 'acme')

    expect(await screen.findByRole('alert')).toHaveTextContent('Error interno del servidor')
  })

})
