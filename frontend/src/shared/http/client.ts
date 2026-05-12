import { client } from '../../api/client.gen'

// Setea solo el baseURL del cliente generado.
// La logica de auth (token, refresh-on-401) la agrega el modulo Auth cuando se implemente.
export function configureHttpClient(): void {
  const apiBaseUrl = import.meta.env.VITE_API_BASE_URL as string | undefined
  if (!apiBaseUrl) {
    throw new Error(
      'VITE_API_BASE_URL no esta definida. Definir en .env.<mode> antes de arrancar.',
    )
  }
  client.setConfig({ baseURL: apiBaseUrl })
}
