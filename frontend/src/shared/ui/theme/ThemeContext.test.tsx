import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ThemeProvider, useTheme } from './ThemeContext'
import { resolveTheme, THEME_ATTRIBUTE, themeStorage } from './themeStorage'

/**
 * Lo que estas pruebas cuidan es la PRECEDENCIA, que es la única lógica que el modo oscuro
 * agrega: la elección del usuario manda sobre la preferencia del sistema, y esa sobre el
 * claro. Lo demás (los colores) no es lógica y lo mide la prueba de contraste.
 */

/** Un `matchMedia` de mentira, porque `happy-dom` no trae preferencia de color. */
function fingirSistema(oscuro: boolean) {
  const oyentes = new Set<(e: MediaQueryListEvent) => void>()
  const consulta = {
    matches: oscuro,
    media: '(prefers-color-scheme: dark)',
    addEventListener: (_: string, f: (e: MediaQueryListEvent) => void) => oyentes.add(f),
    removeEventListener: (_: string, f: (e: MediaQueryListEvent) => void) => oyentes.delete(f),
  }
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => consulta),
  )
  return {
    /**
     * Simula que el sistema operativo cambia de tema con la aplicación abierta. Va dentro de
     * `act` porque el cambio entra por un oyente y no por un evento del usuario: sin eso
     * React no vuelve a pintar antes de la aserción y la prueba mide el estado viejo.
     */
    cambiaA(nuevoOscuro: boolean) {
      consulta.matches = nuevoOscuro
      act(() => {
        for (const f of oyentes) f({ matches: nuevoOscuro } as MediaQueryListEvent)
      })
    },
    get oyentes() {
      return oyentes.size
    },
  }
}

function Sonda() {
  const { theme, elegido, toggleTheme } = useTheme()
  return (
    <>
      <p>
        tema:{theme} elegido:{String(elegido)}
      </p>
      <button type="button" onClick={toggleTheme}>
        cambiar
      </button>
    </>
  )
}

const texto = () => screen.getByText(/^tema:/).textContent

beforeEach(() => {
  document.documentElement.removeAttribute(THEME_ATTRIBUTE)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('el atributo', () => {
  /**
   * Las aserciones de abajo leen con la misma constante con la que el código escribe, así que
   * no pueden ver un cambio de nombre. Esto lo ata: si alguien renombra el atributo, el CSS
   * deja de matchear y esta línea es la que lo dice.
   */
  it('es el que el CSS mira', () => {
    expect(THEME_ATTRIBUTE).toBe('data-theme')
  })
})

describe('la precedencia', () => {
  it('sin elección y con el sistema en oscuro, arranca oscuro', () => {
    fingirSistema(true)
    render(
      <ThemeProvider>
        <Sonda />
      </ThemeProvider>,
    )
    expect(texto()).toBe('tema:dark elegido:false')
    expect(document.documentElement.getAttribute(THEME_ATTRIBUTE)).toBe('dark')
  })

  it('sin elección y con el sistema en claro, arranca claro', () => {
    fingirSistema(false)
    render(
      <ThemeProvider>
        <Sonda />
      </ThemeProvider>,
    )
    expect(texto()).toBe('tema:light elegido:false')
  })

  /** El caso que distingue "no elegí" de "elegí claro", que es toda la razón del `null`. */
  it('la elección guardada le gana al sistema', () => {
    fingirSistema(true)
    themeStorage.set('light')
    render(
      <ThemeProvider>
        <Sonda />
      </ThemeProvider>,
    )
    expect(texto()).toBe('tema:light elegido:true')
  })

  it('sin sistema que responda, cae en claro', () => {
    vi.stubGlobal('matchMedia', undefined)
    render(
      <ThemeProvider>
        <Sonda />
      </ThemeProvider>,
    )
    expect(texto()).toBe('tema:light elegido:false')
  })
})

describe('la escucha del sistema', () => {
  it('mientras el usuario no eligió, el cambio del sistema arrastra a la aplicación', () => {
    const sistema = fingirSistema(false)
    render(
      <ThemeProvider>
        <Sonda />
      </ThemeProvider>,
    )
    expect(texto()).toBe('tema:light elegido:false')
    sistema.cambiaA(true)
    expect(texto()).toBe('tema:dark elegido:false')
  })

  /**
   * Y la vuelta, que es la mitad que ninguna prueba hacía: el sistema volviendo a claro. Con
   * un solo caso que va de claro a oscuro, cambiar el destino de la rama falsa por otro
   * `'dark'` sobrevive, y entonces el sistema arrastra en un solo sentido.
   */
  it('el cambio del sistema arrastra en los dos sentidos', () => {
    const sistema = fingirSistema(true)
    render(
      <ThemeProvider>
        <Sonda />
      </ThemeProvider>,
    )
    expect(texto()).toBe('tema:dark elegido:false')
    sistema.cambiaA(false)
    expect(texto()).toBe('tema:light elegido:false')
  })

  /**
   * Y esta es la mitad que se olvida: después de elegir a mano, el sistema deja de mandar.
   * Sin esto, el usuario elige claro y media hora después el sistema se lo da vuelta solo.
   */
  it('después de elegir a mano, el sistema deja de mandar', async () => {
    const sistema = fingirSistema(false)
    render(
      <ThemeProvider>
        <Sonda />
      </ThemeProvider>,
    )
    await userEvent.click(screen.getByRole('button'))
    expect(texto()).toBe('tema:dark elegido:true')
    sistema.cambiaA(false)
    expect(texto()).toBe('tema:dark elegido:true')
    expect(sistema.oyentes, 'el oyente del sistema se tiene que soltar').toBe(0)
  })
})

/**
 * El interruptor va y VUELVE. Con un solo clic medido, fijar el destino en oscuro sobrevive:
 * el control prende el modo oscuro y ya no lo apaga nunca. Lo midió la revisión de este PR.
 */
describe('el interruptor', () => {
  it('dos clics vuelven al tema de partida', async () => {
    fingirSistema(false)
    render(
      <ThemeProvider>
        <Sonda />
      </ThemeProvider>,
    )
    const boton = screen.getByRole('button')
    await userEvent.click(boton)
    expect(texto()).toBe('tema:dark elegido:true')
    await userEvent.click(boton)
    expect(texto()).toBe('tema:light elegido:true')
    expect(themeStorage.get(), 'la vuelta también se guarda').toBe('light')
    expect(document.documentElement.getAttribute(THEME_ATTRIBUTE)).toBe('light')
  })
})

/**
 * La barra del navegador en el móvil. Vive fuera de la página, así que ninguna hoja de estilos la
 * alcanza: la escribe el atributo de una etiqueta, y por eso hace falta código. El valor sale del
 * token del fondo de página ya resuelto y no de un literal, para que no se separe del tema.
 *
 * La suite corre con el CSS apagado, así que el token no está calculado: se le pone a mano el
 * valor que tendría, que es lo que permite medir el mecanismo sin depender de la hoja.
 */
describe('el color de la barra del navegador', () => {
  it('sigue al tema que el usuario elige', async () => {
    const meta = document.createElement('meta')
    meta.setAttribute('name', 'theme-color')
    meta.setAttribute('content', '#f8fafc')
    document.head.appendChild(meta)
    document.documentElement.style.setProperty('--color-canvas', '#0b1626')
    fingirSistema(false)
    render(
      <ThemeProvider>
        <Sonda />
      </ThemeProvider>,
    )

    await userEvent.click(screen.getByRole('button'))

    expect(meta.getAttribute('content'), 'la barra se quedó con el color del tema anterior').toBe(
      '#0b1626',
    )
    document.documentElement.style.removeProperty('--color-canvas')
    meta.remove()
  })

  it('no rompe cuando el documento no trae la etiqueta', () => {
    expect(document.querySelector('meta[name="theme-color"]')).toBeNull()
    fingirSistema(true)
    expect(() =>
      render(
        <ThemeProvider>
          <Sonda />
        </ThemeProvider>,
      ),
    ).not.toThrow()
  })
})

describe('la persistencia', () => {
  it('la elección sobrevive a recargar', async () => {
    fingirSistema(false)
    const { unmount } = render(
      <ThemeProvider>
        <Sonda />
      </ThemeProvider>,
    )
    await userEvent.click(screen.getByRole('button'))
    expect(themeStorage.get()).toBe('dark')
    unmount()

    render(
      <ThemeProvider>
        <Sonda />
      </ThemeProvider>,
    )
    expect(texto()).toBe('tema:dark elegido:true')
  })
})

/**
 * El script del `<head>` repite la precedencia en JavaScript plano porque corre antes de que
 * exista un módulo. Dos copias de una regla se separan: esto afirma que la copia sigue
 * nombrando las mismas tres piezas, que es lo que se puede afirmar sin ejecutarla.
 */
describe('el script que evita el destello', () => {
  const html = readFileSync(join(process.cwd(), 'index.html'), 'utf8')

  it('está en el `head`, es sincrónico y escribe el atributo', () => {
    const cabeza = html.slice(0, html.indexOf('</head>'))
    expect(cabeza).toContain('scaramutti.theme')
    expect(cabeza).toMatch(/prefers-color-scheme: dark/)
    // Las DOS escrituras, y por separado: el script tiene un `catch` que también escribe el
    // atributo, así que un patrón laxo se queda contento con el camino de error mientras el
    // camino normal se borró. Medido: una mutación que borraba el bueno sobrevivía.
    expect(cabeza, 'el camino normal tiene que escribir el tema resuelto').toMatch(
      /setAttribute\('data-theme', tema\)/,
    )
    expect(cabeza, 'y si algo falla, tiene que quedar en claro y no sin atributo').toMatch(
      /setAttribute\('data-theme', 'light'\)/,
    )
    expect(cabeza, 'un script diferido volvería a dejar el destello').not.toMatch(
      /<script[^>]*\b(defer|async|type="module")/,
    )
  })

  it('usa la misma clave que el módulo', () => {
    const modulo = readFileSync(join(process.cwd(), 'src', 'shared', 'ui', 'theme', 'themeStorage.ts'), 'utf8')
    const clave = /'([a-z.]+\.theme)'/.exec(modulo)?.[1]
    expect(clave).toBeDefined()
    expect(html).toContain(`'${clave}'`)
  })

  /**
   * Y la precedencia de la copia, EJECUTÁNDOLA. Afirmar la clave no alcanzaba: durante la
   * revisión se dio vuelta el ternario del script y las diez pruebas siguieron pasando, o sea
   * que la copia podía invertir el orden entero sin que nadie lo viera. El daño no es teórico:
   * quien eligió claro con el sistema en oscuro recibe una primera pintura oscura que React
   * corrige, que es el destello que este script existe para evitar, en la dirección que más
   * molesta.
   *
   * Se ejecuta el cuerpo real extraído del `index.html`, con un `window` y un `document` de
   * mentira, así que mide la copia y no una transcripción suya.
   */
  describe('la precedencia de la copia, ejecutada', () => {
    const cuerpo = /<script>([\s\S]*?)<\/script>/.exec(html)?.[1] ?? ''
    /** Lo último que el script escribe en la barra del navegador, para poder afirmarlo. */
    let pintado: string | null = null

    /** `'lanza'` es el almacenamiento bloqueado: cookies apagadas o `dom.storage` en falso. */
    type Guardado = string | null | 'lanza'
    type Sistema = boolean | 'sin matchMedia'

    function correr(guardado: Guardado, sistemaOscuro: Sistema) {
      let escrito: string | null = null
      const barra = { setAttribute: (_: string, v: string) => (pintado = v) }
      const ventana = {
        localStorage: {
          getItem: () => {
            if (guardado === 'lanza') throw new Error('bloqueado')
            return guardado
          },
        },
        matchMedia: sistemaOscuro === 'sin matchMedia' ? undefined : () => ({ matches: sistemaOscuro }),
      }
      // `querySelector` incluido: el script también escribe el color de la barra del navegador,
      // y sin él la copia lanza y estas pruebas medirían el `catch` en vez del camino bueno.
      const documento = {
        documentElement: { setAttribute: (_: string, v: string) => (escrito = v) },
        querySelector: () => barra,
      }
      new Function('window', 'document', cuerpo)(ventana, documento)
      return escrito
    }

    it('el cuerpo del script se pudo extraer', () => {
      expect(cuerpo).toContain('scaramutti.theme')
    })

    /**
     * Y que pinte la barra del navegador en la primera carga, no solo al montar React: si esperara
     * al proveedor, quien abre la aplicación en oscuro desde el móvil ve la barra clara hasta que
     * la aplicación monta, que es el mismo destello que este script existe para evitar.
     */
    it('pinta la barra del navegador con el tema resuelto', () => {
      correr('dark', false)
      expect(pintado).toBe('#0b1626')
      correr('light', true)
      expect(pintado).toBe('#f8fafc')
    })

    it('lo guardado le gana al sistema', () => {
      expect(correr('light', true)).toBe('light')
      expect(correr('dark', false)).toBe('dark')
    })

    it('sin nada guardado manda el sistema', () => {
      expect(correr(null, true)).toBe('dark')
      expect(correr(null, false)).toBe('light')
    })

    it('un valor corrupto cae en el sistema y no se escribe tal cual', () => {
      expect(correr('banana', true)).toBe('dark')
      expect(correr('"><script>', false)).toBe('light')
    })

    it('sin sistema que responda, claro', () => {
      expect(correr(null, 'sin matchMedia')).toBe('light')
    })

    /**
     * Y acá las DOS fuentes, con el mismo entorno y comparadas entre sí, que es lo que el
     * comentario del `index.html` promete. Afirmar cada lado por separado no alcanza: la
     * revisión encontró que con el almacenamiento bloqueado el script resolvía claro y el
     * módulo oscuro, porque el `try` del script abarcaba también la rama del sistema. Dos
     * mitades verdes congelaban la divergencia en vez de verla, y el daño era el destello que
     * este script existe para evitar.
     */
    describe('las dos copias resuelven igual', () => {
      const entornos: Array<[Guardado, Sistema, string]> = [
        ['light', true, 'light'],
        ['dark', false, 'dark'],
        [null, true, 'dark'],
        [null, false, 'light'],
        ['banana', true, 'dark'],
        [null, 'sin matchMedia', 'light'],
        ['lanza', true, 'dark'],
        ['lanza', false, 'light'],
        ['lanza', 'sin matchMedia', 'light'],
      ]

      /** El módulo, con el mismo entorno que se le da al script. */
      function modulo(guardado: Guardado, sistemaOscuro: Sistema): string {
        const espia = vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
          if (guardado === 'lanza') throw new Error('bloqueado')
          return guardado
        })
        vi.stubGlobal(
          'matchMedia',
          sistemaOscuro === 'sin matchMedia' ? undefined : vi.fn(() => ({ matches: sistemaOscuro })),
        )
        try {
          return resolveTheme()
        } finally {
          espia.mockRestore()
        }
      }

      it.each(entornos)('guardado %s y sistema %s dan %s en las dos', (guardado, sistema, esperado) => {
        expect(correr(guardado, sistema), 'el script del encabezado').toBe(esperado)
        expect(modulo(guardado, sistema), 'el módulo').toBe(esperado)
      })
    })
  })
})

describe('useTheme fuera del proveedor', () => {
  it('falla fuerte en vez de devolver un tema inventado', () => {
    const silencio = vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => render(<Sonda />)).toThrow(/ThemeProvider/)
    silencio.mockRestore()
  })
})
