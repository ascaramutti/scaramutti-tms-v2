/**
 * Garantiza que una promise tarde al menos `minMs` milisegundos en resolver/rechazar.
 * Si la promise termina antes, se espera el delta. Si tarda igual o mas, no agrega delay.
 *
 * Util para mostrar loaders por un minimo de tiempo (evita "flicker" cuando la
 * operacion es muy rapida). En tests el caller deberia pasar 0 para no ralentizar.
 */
export async function withMinDuration<T>(promise: Promise<T>, minMs: number): Promise<T> {
  const start = Date.now()
  try {
    const value = await promise
    await waitRemaining(start, minMs)
    return value
  } catch (error) {
    await waitRemaining(start, minMs)
    throw error
  }
}

async function waitRemaining(start: number, minMs: number): Promise<void> {
  const elapsed = Date.now() - start
  const remaining = minMs - elapsed
  if (remaining > 0) {
    await new Promise((resolve) => setTimeout(resolve, remaining))
  }
}
