import { describe, expect, it } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { SEARCH_DEBOUNCE_MS, WorkersSearchPage } from './WorkersSearchPage'
import { WORKERS_BASE } from '../../../shared/paths'
import { server } from '../../../test/mocks/server'
import {
  fakeWorker,
  workersSearchByStatus,
  workersSearchCapture,
  workersSearchError,
  workersSearchPage,
  workersSearchSlow,
} from '../../../test/mocks/handlers/shared-catalogs'
import type { ProductsCaptureSink } from '../../../test/mocks/handlers/warehouse'

/** Más que el rebote: separa "no se disparó" de "todavía no se disparó". */
const DESPUES_DEL_REBOTE = SEARCH_DEBOUNCE_MS + 150

const ANA = fakeWorker({ id: 7, fullName: 'Ana Torres Ruiz', position: 'Despachadora' })
const LUIS = fakeWorker({ id: 12, fullName: 'Luis Quispe Mamani', position: 'Chofer', isActive: false })
const EVA = fakeWorker({ id: 21, fullName: 'Eva Torres Paz', position: 'Almacenera' })
const ROSA = fakeWorker({ id: 15, fullName: 'Rosa Vega Solís', position: 'Ayudante', isActive: false })

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[WORKERS_BASE]}>
        <WorkersSearchPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function esperarElRebote() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, DESPUES_DEL_REBOTE))
  })
}

function campo() {
  return screen.getByLabelText(/buscar trabajador/i)
}

/** La región propia de la página: el spinner también anuncia, y una consulta suelta lo encontraría a él. */
function regionViva(container: HTMLElement) {
  return container.querySelector('p.sr-only[aria-live="polite"]')
}

function filaDe(nombre: string) {
  return screen.getByText(nombre).closest('tr') as HTMLElement
}

describe('WorkersSearchPage', () => {
  it('muestra el título de la pantalla', () => {
    server.use(workersSearchPage([]))
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: /^trabajadores$/i })).toBeInTheDocument()
  })

  it('al abrir no busca ni muestra el vacío', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(workersSearchCapture(sink, [ANA]))
    renderPage()

    await esperarElRebote()

    expect(sink.calls).toEqual([])
    expect(screen.queryByText('No encontramos trabajadores con ese texto')).not.toBeInTheDocument()
  })

  /** El handler devuelve un trabajador: con la lista vacía, no ver nada no probaría nada. */
  it('con menos de 3 caracteres no llama al backend', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(workersSearchCapture(sink, [ANA]))
    renderPage()

    await user.type(campo(), 'an')
    await esperarElRebote()

    expect(sink.calls).toEqual([])
    expect(screen.queryByText('Ana Torres Ruiz')).not.toBeInTheDocument()
  })

  it.each(['  an  ', '   '])('los espacios no cuentan para el mínimo (%j)', async (texto) => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(workersSearchCapture(sink, [ANA]))
    const { container } = renderPage()

    await user.type(campo(), texto)
    await esperarElRebote()

    // Ni llamada, ni vacío, ni anuncio: sin término no hubo búsqueda. Y la pista sigue.
    expect(sink.calls).toEqual([])
    expect(screen.queryByText('No encontramos trabajadores con ese texto')).not.toBeInTheDocument()
    expect(regionViva(container)).toHaveTextContent(/^$/)
    expect(screen.getByText(/ingresa al menos 3 caracteres/i)).toBeInTheDocument()
  })

  it('busca a partir del tercer caracter, con el término sin espacios', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(workersSearchCapture(sink, [ANA]))
    renderPage()

    await user.type(campo(), ' ana ')

    await screen.findByText('Ana Torres Ruiz')
    expect(sink.params?.get('q')).toBe('ana')
  })

  /** El valor es el de las demás búsquedas; las esperas de esta suite salen de él. */
  it('el rebote es de 300 ms', () => {
    expect(SEARCH_DEBOUNCE_MS).toBe(300)
  })

  /** Con pausas de medio rebote entre teclas, como quien escribe: un rebote corto buscaría por tecla. */
  it('escribir de corrido dispara una sola búsqueda', async () => {
    const user = userEvent.setup({ delay: SEARCH_DEBOUNCE_MS / 2 })
    const sink: ProductsCaptureSink = {}
    server.use(workersSearchCapture(sink, [ANA]))
    renderPage()

    await user.type(campo(), 'torres')
    await screen.findByText('Ana Torres Ruiz')
    await esperarElRebote()

    expect(sink.calls).toHaveLength(1)
  })

  /** Fija el rebote desde el otro lado: sin esto, alargarlo deja vacías las esperas negativas. */
  it('busca en cuanto pasa el rebote, y no antes', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(workersSearchCapture(sink, [ANA]))
    renderPage()

    await user.type(campo(), 'ana')
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, SEARCH_DEBOUNCE_MS - 100))
    })
    expect(sink.calls).toEqual([])

    // Hasta 150 ms después del rebote: margen para una máquina cargada, no para un rebote más largo.
    await waitFor(() => expect(sink.calls).toHaveLength(1), { timeout: 250 })
  })

  /** La lista anterior sigue en caché: lo que la saca de la pantalla es que el término ya no alcanza. */
  it('borrar por debajo del mínimo saca la tabla y calla la región', async () => {
    const user = userEvent.setup()
    server.use(workersSearchPage([ANA]))
    const { container } = renderPage()

    await user.type(campo(), 'ana')
    await screen.findByText('Ana Torres Ruiz')
    await user.type(campo(), '{backspace}')
    await esperarElRebote()

    expect(screen.queryByText('Ana Torres Ruiz')).not.toBeInTheDocument()
    expect(regionViva(container)).toHaveTextContent(/^$/)
    expect(screen.getByText(/ingresa al menos 3 caracteres/i)).toBeInTheDocument()
  })

  describe('filtro de estado', () => {
    it('se abre en Activos y pide solo activos', async () => {
      const user = userEvent.setup()
      const sink: ProductsCaptureSink = {}
      server.use(workersSearchCapture(sink, [ANA]))
      renderPage()

      expect(screen.getByLabelText('Estado')).toHaveValue('active')
      await user.type(campo(), 'ana')

      await screen.findByText('Ana Torres Ruiz')
      expect(sink.params?.get('isActive')).toBe('true')
    })

    it('Activos muestra solo a los activos', async () => {
      const user = userEvent.setup()
      server.use(workersSearchByStatus([ANA, LUIS]))
      renderPage()

      await user.type(campo(), 'tor')

      await screen.findByText('Ana Torres Ruiz')
      expect(screen.queryByText('Luis Quispe Mamani')).not.toBeInTheDocument()
    })

    it('Inactivos pide isActive=false', async () => {
      const user = userEvent.setup()
      const sink: ProductsCaptureSink = {}
      server.use(workersSearchCapture(sink, [LUIS]))
      renderPage()

      await user.selectOptions(screen.getByLabelText('Estado'), 'Inactivos')
      await user.type(campo(), 'luis')

      await screen.findByText('Luis Quispe Mamani')
      expect(sink.params?.get('isActive')).toBe('false')
    })

    /** El filtro se elige antes de escribir: sin resultados previos, lo visible es la respuesta a este pedido. */
    it('Inactivos muestra solo a los inactivos', async () => {
      const user = userEvent.setup()
      server.use(workersSearchByStatus([ANA, LUIS]))
      renderPage()

      await user.selectOptions(screen.getByLabelText('Estado'), 'Inactivos')
      await user.type(campo(), 'tor')

      await screen.findByText('Luis Quispe Mamani')
      expect(screen.queryByText('Ana Torres Ruiz')).not.toBeInTheDocument()
    })

    /** "Todos" no manda el parámetro: con `isActive=true` o `false` el backend recortaría. */
    it('Todos no manda isActive', async () => {
      const user = userEvent.setup()
      const sink: ProductsCaptureSink = {}
      server.use(workersSearchCapture(sink, [ANA]))
      renderPage()

      await user.selectOptions(screen.getByLabelText('Estado'), 'Todos')
      await user.type(campo(), 'ana')

      await screen.findByText('Ana Torres Ruiz')
      expect(sink.params?.has('isActive')).toBe(false)
    })

    it('Todos muestra a activos e inactivos', async () => {
      const user = userEvent.setup()
      server.use(workersSearchByStatus([ANA, LUIS]))
      renderPage()

      await user.selectOptions(screen.getByLabelText('Estado'), 'Todos')
      await user.type(campo(), 'tor')

      await screen.findByText('Ana Torres Ruiz')
      expect(screen.getByText('Luis Quispe Mamani')).toBeInTheDocument()
    })

    /** Mientras llega la lista nueva, la anterior sigue a la vista y marcada como ocupada. */
    it('al cambiar el estado conserva la lista anterior mientras busca', async () => {
      const user = userEvent.setup()
      server.use(workersSearchPage([ANA]))
      renderPage()

      await user.type(campo(), 'ana')
      await screen.findByText('Ana Torres Ruiz')
      server.use(workersSearchSlow([LUIS], 300))
      await user.selectOptions(screen.getByLabelText('Estado'), 'Todos')

      await waitFor(() =>
        expect(screen.getByRole('table').closest('[aria-busy]')).toHaveAttribute('aria-busy', 'true'),
      )
      expect(screen.getByText('Ana Torres Ruiz')).toBeInTheDocument()
      expect(await screen.findByText('Luis Quispe Mamani')).toBeInTheDocument()
    })

    it('cambiar el estado vuelve a buscar con el mismo término', async () => {
      const user = userEvent.setup()
      const sink: ProductsCaptureSink = {}
      server.use(workersSearchCapture(sink, [ANA]))
      renderPage()

      await user.type(campo(), 'ana')
      await screen.findByText('Ana Torres Ruiz')
      await user.selectOptions(screen.getByLabelText('Estado'), 'Inactivos')

      await waitFor(() => expect(sink.calls).toHaveLength(2))
      expect(sink.calls?.[1].get('q')).toBe('ana')
      expect(sink.calls?.[1].get('isActive')).toBe('false')
    })

    it('cambiar el estado sin el mínimo escrito no llama al backend', async () => {
      const user = userEvent.setup()
      const sink: ProductsCaptureSink = {}
      server.use(workersSearchCapture(sink, [ANA]))
      renderPage()

      await user.type(campo(), 'an')
      await user.selectOptions(screen.getByLabelText('Estado'), 'Todos')
      await esperarElRebote()

      expect(sink.calls).toEqual([])
    })
  })

  describe('resultados', () => {
    it('la tabla tiene las tres columnas', async () => {
      const user = userEvent.setup()
      server.use(workersSearchPage([ANA]))
      renderPage()

      await user.type(campo(), 'ana')
      await screen.findByText('Ana Torres Ruiz')

      expect(screen.getByRole('table', { name: 'Trabajadores encontrados' })).toBeInTheDocument()
      const encabezados = screen.getAllByRole('columnheader').map((th) => th.textContent)
      expect(encabezados).toEqual(['Nombre completo', 'Cargo', 'Estado'])
    })

    /** Dos trabajadores con cargos distintos: con uno solo, cruzar columnas pasaría igual. */
    it('cada fila muestra el nombre, el cargo y el estado de ese trabajador', async () => {
      const user = userEvent.setup()
      server.use(workersSearchPage([ANA, LUIS]))
      renderPage()

      await user.type(campo(), 'tor')
      await screen.findByText('Ana Torres Ruiz')

      const celdasAna = within(filaDe('Ana Torres Ruiz')).getAllByRole('cell').map((td) => td.textContent)
      const celdasLuis = within(filaDe('Luis Quispe Mamani')).getAllByRole('cell').map((td) => td.textContent)
      expect(celdasAna).toEqual(['Ana Torres Ruiz', 'Despachadora', 'Activo'])
      expect(celdasLuis).toEqual(['Luis Quispe Mamani', 'Chofer', 'Inactivo'])
    })

    /** El texto va siempre; el tono distingue de un vistazo a quien ya no está. */
    it('la etiqueta de inactivo no usa el tono de éxito', async () => {
      const user = userEvent.setup()
      server.use(workersSearchPage([ANA, LUIS]))
      renderPage()

      await user.type(campo(), 'tor')
      await screen.findByText('Ana Torres Ruiz')

      expect(within(filaDe('Ana Torres Ruiz')).getByText('Activo').className).toMatch(/success/)
      expect(within(filaDe('Luis Quispe Mamani')).getByText('Inactivo').className).not.toMatch(/success/)
    })

    it('un trabajador sin cargo muestra una raya', async () => {
      const user = userEvent.setup()
      server.use(workersSearchPage([fakeWorker({ id: 3, fullName: 'Rosa Vega Solís', position: null })]))
      renderPage()

      await user.type(campo(), 'ros')
      await screen.findByText('Rosa Vega Solís')

      expect(within(filaDe('Rosa Vega Solís')).getAllByRole('cell')[1]).toHaveTextContent('—')
    })

    /** El padrón es de consulta hasta que exista la ficha: una fila que navega llevaría a una ruta vacía. */
    it('las filas no navegan', async () => {
      const user = userEvent.setup()
      server.use(workersSearchPage([ANA]))
      renderPage()

      await user.type(campo(), 'ana')
      await screen.findByText('Ana Torres Ruiz')

      const fila = filaDe('Ana Torres Ruiz')
      expect(fila).not.toHaveAttribute('role', 'button')
      expect(fila).not.toHaveAttribute('tabindex')
      expect(within(fila).queryByRole('link')).not.toBeInTheDocument()
    })

    it('no ofrece dar de alta un trabajador', async () => {
      const user = userEvent.setup()
      server.use(workersSearchPage([]))
      renderPage()

      await user.type(campo(), 'zzz')
      await screen.findByText('No encontramos trabajadores con ese texto')

      // Ningún botón ni enlace, se llame como se llame: el alta llega con su propia pantalla.
      expect(screen.queryByRole('button')).not.toBeInTheDocument()
      expect(screen.queryByRole('link')).not.toBeInTheDocument()
    })

    it('muestra el estado de carga mientras busca', async () => {
      const user = userEvent.setup()
      server.use(workersSearchSlow([ANA], 120))
      renderPage()

      await user.type(campo(), 'ana')

      await waitFor(() => expect(screen.getByLabelText('Buscando trabajadores')).toBeInTheDocument())
      expect(await screen.findByText('Ana Torres Ruiz')).toBeInTheDocument()
    })
  })

  describe('vacío y error', () => {
    /** Un solo texto para los tres estados: no remite al filtro. */
    it.each(['Activos', 'Inactivos', 'Todos'])('sin coincidencias lo dice con el texto de la historia (%s)', async (filtro) => {
      const user = userEvent.setup()
      server.use(workersSearchPage([]))
      renderPage()

      await user.selectOptions(screen.getByLabelText('Estado'), filtro)
      await user.type(campo(), 'zzz')

      expect(await screen.findByText('No encontramos trabajadores con ese texto')).toBeInTheDocument()
      expect(screen.getByText('Revisa la escritura o prueba con el número de documento.')).toBeInTheDocument()
      expect(screen.queryByText(/filtro/i)).not.toBeInTheDocument()
    })

    /** Mientras llega Todos, un vacío heredado de Activos se leería como la respuesta de Todos. */
    it('al cambiar de estado desde un vacío, muestra la carga y no el vacío del estado nuevo', async () => {
      const user = userEvent.setup()
      server.use(workersSearchPage([]))
      renderPage()

      await user.type(campo(), 'luis')
      await screen.findByText('No encontramos trabajadores con ese texto')
      server.use(workersSearchSlow([LUIS], 300))
      await user.selectOptions(screen.getByLabelText('Estado'), 'Todos')

      await waitFor(() => expect(screen.getByLabelText('Buscando trabajadores')).toBeInTheDocument())
      expect(screen.queryByText('No encontramos trabajadores con ese texto')).not.toBeInTheDocument()
      expect(await screen.findByText('Luis Quispe Mamani')).toBeInTheDocument()
    })

    /** La lista de Activos no puede quedar a la vista bajo el filtro Inactivos si su búsqueda falla. */
    it('si falla la búsqueda de otro estado, no queda la lista anterior', async () => {
      const user = userEvent.setup()
      server.use(workersSearchPage([ANA]))
      const { container } = renderPage()

      await user.type(campo(), 'ana')
      await screen.findByText('Ana Torres Ruiz')
      server.use(workersSearchError(500, { detail: 'Servicio caído' }))
      await user.selectOptions(screen.getByLabelText('Estado'), 'Inactivos')

      expect(await screen.findByRole('alert')).toHaveTextContent('Servicio caído')
      expect(screen.queryByText('Ana Torres Ruiz')).not.toBeInTheDocument()
      expect(regionViva(container)).toHaveTextContent(/^$/)
    })

    /** Una caída no es "ese trabajador no existe". */
    it('un error se explica con el mensaje del backend y no como vacío', async () => {
      const user = userEvent.setup()
      server.use(workersSearchError(500, { detail: 'Error interno del servidor' }))
      renderPage()

      await user.type(campo(), 'ana')

      expect(await screen.findByRole('alert')).toHaveTextContent('Error interno del servidor')
      expect(screen.queryByText('No encontramos trabajadores con ese texto')).not.toBeInTheDocument()
    })

    it('un error sin explicación del backend anuncia el suyo, y la región no dice "Sin resultados"', async () => {
      const user = userEvent.setup()
      server.use(workersSearchError(500, { detail: undefined }))
      const { container } = renderPage()

      await user.type(campo(), 'ana')

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'No se pudieron buscar trabajadores. Intenta de nuevo.',
      )
      expect(regionViva(container)).toHaveTextContent(/^$/)
    })

    it('reintentar vuelve a buscar y muestra los resultados', async () => {
      const user = userEvent.setup()
      server.use(workersSearchError(500))
      renderPage()

      await user.type(campo(), 'ana')
      await screen.findByRole('alert')

      server.use(workersSearchPage([ANA]))
      await user.click(screen.getByRole('button', { name: /reintentar/i }))

      expect(await screen.findByText('Ana Torres Ruiz')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })
  })

  describe('campo de búsqueda', () => {
    it('topea el término en el máximo que el backend acepta', () => {
      server.use(workersSearchPage([]))
      renderPage()
      expect(campo()).toHaveAttribute('maxlength', '200')
    })

    it('la pista del mínimo y el placeholder están desde que se abre, y la pista se va al llegar al mínimo', async () => {
      const user = userEvent.setup()
      server.use(workersSearchPage([]))
      renderPage()

      expect(screen.getByText(/ingresa al menos 3 caracteres/i)).toBeInTheDocument()
      expect(campo()).toHaveAttribute('aria-describedby', 'q-hint')
      // El id tiene que existir: una referencia colgada deja al lector sin instrucción.
      expect(campo()).toHaveAccessibleDescription(/ingresa al menos 3 caracteres/i)
      expect(campo()).toHaveAttribute('placeholder', 'Nombre, apellido o documento')

      await user.type(campo(), 'ana')

      await waitFor(() =>
        expect(screen.queryByText(/ingresa al menos 3 caracteres/i)).not.toBeInTheDocument(),
      )
      expect(campo()).not.toHaveAttribute('aria-describedby')
    })
  })

  describe('región viva', () => {
    /** Mismo número de filas en los dos estados: sin el nombre del estado, el anuncio final repetiría el anterior. */
    it('al cambiar el filtro anuncia el estado nuevo, y no la lista anterior con él', async () => {
      const user = userEvent.setup()
      server.use(workersSearchPage([ANA]))
      const { container } = renderPage()

      await user.type(campo(), 'ana')
      await waitFor(() => expect(regionViva(container)).toHaveTextContent(/^1 trabajador activo$/))

      server.use(workersSearchSlow([LUIS], 300))
      await user.selectOptions(screen.getByLabelText('Estado'), 'Inactivos')
      await waitFor(() =>
        expect(screen.getByRole('table').closest('[aria-busy]')).toHaveAttribute('aria-busy', 'true'),
      )
      expect(regionViva(container)).toHaveTextContent(/^$/)

      await waitFor(() => expect(regionViva(container)).toHaveTextContent(/^1 trabajador inactivo$/))
    })

    /** Una región que entra al árbol con su texto no se anuncia: tiene que ser el mismo nodo desde el principio. */
    it('está montada desde que se abre y es el mismo nodo que anuncia', async () => {
      const user = userEvent.setup()
      server.use(workersSearchSlow([ANA], 120))
      const { container } = renderPage()
      const alAbrir = regionViva(container)
      expect(alAbrir).not.toBeNull()

      await user.type(campo(), 'ana')
      await waitFor(() => expect(screen.getByLabelText('Buscando trabajadores')).toBeInTheDocument())
      // Mientras busca no anuncia nada: "Sin resultados" sería falso.
      expect(regionViva(container)).toHaveTextContent(/^$/)

      await screen.findByText('Ana Torres Ruiz')
      expect(regionViva(container)).toBe(alAbrir)
      expect(alAbrir).toHaveTextContent(/^1 trabajador activo$/)
    })

    /** Texto exacto: "1 trabajadores" contiene "1 trabajador" y pasaría por subcadena. */
    it.each([
      [/^1 trabajador activo$/, [ANA], 'Activos'],
      [/^2 trabajadores activos$/, [ANA, EVA], 'Activos'],
      [/^Sin trabajadores activos$/, [], 'Activos'],
      [/^1 trabajador inactivo$/, [LUIS], 'Inactivos'],
      [/^2 trabajadores inactivos$/, [LUIS, ROSA], 'Inactivos'],
      [/^Sin trabajadores inactivos$/, [], 'Inactivos'],
      [/^1 trabajador$/, [ANA], 'Todos'],
      [/^2 trabajadores$/, [ANA, LUIS], 'Todos'],
      [/^Sin resultados$/, [], 'Todos'],
    ])('anuncia cuántos encontró: %s', async (anuncio, content, filtro) => {
      const user = userEvent.setup()
      server.use(workersSearchPage(content))
      const { container } = renderPage()

      await user.selectOptions(screen.getByLabelText('Estado'), filtro)
      await user.type(campo(), 'tor')

      await waitFor(() => expect(regionViva(container)).toHaveTextContent(anuncio))
    })
  })

  describe('accesibilidad', () => {
    it('sin violaciones con resultados', async () => {
      const user = userEvent.setup()
      server.use(workersSearchPage([ANA, LUIS]))
      const { container } = renderPage()

      await user.type(campo(), 'tor')
      await screen.findByText('Ana Torres Ruiz')

      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones con el vacío', async () => {
      const user = userEvent.setup()
      server.use(workersSearchPage([]))
      const { container } = renderPage()

      await user.type(campo(), 'zzz')
      await screen.findByText('No encontramos trabajadores con ese texto')

      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones con el error', async () => {
      const user = userEvent.setup()
      server.use(workersSearchError(500))
      const { container } = renderPage()

      await user.type(campo(), 'ana')
      await screen.findByRole('alert')

      expect(await axe(container)).toHaveNoViolations()
    })
  })
})
