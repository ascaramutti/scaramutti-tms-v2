/**
 * Oculta la página como el navegador, fuera de `act`, y devuelve su texto en ese mismo instante:
 * lo que React no pintó de forma síncrona sigue ahí, como en la página que congela la caché de ida y
 * vuelta.
 */
export function hidePageAndReadText(): string {
  const environment = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
  const previous = environment.IS_REACT_ACT_ENVIRONMENT
  environment.IS_REACT_ACT_ENVIRONMENT = false
  try {
    window.dispatchEvent(new Event('pagehide'))
    return document.body.textContent ?? ''
  } finally {
    environment.IS_REACT_ACT_ENVIRONMENT = previous
  }
}
