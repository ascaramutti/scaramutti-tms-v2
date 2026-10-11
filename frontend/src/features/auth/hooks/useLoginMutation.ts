import { useMutation } from '@tanstack/react-query'
import { login, type LoginRequest, type LoginResponse } from '../../../api'

// La mutation usa `throwOnError: true` para que axios rechace la promesa
// cuando el backend responde 4xx. Asi react-query dispara `onError` con un
// AxiosError que el llamador puede parsear (status + Problem.body).
async function performLogin(body: LoginRequest): Promise<LoginResponse> {
  const { data } = await login({ body, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacia del backend en /auth/login')
  }
  return data
}

// gcTime 0: la caché de mutaciones guarda las variables, y acá son la contraseña. Así no sigue
// en memoria una vez que la pantalla de ingreso se desmonta.
export function useLoginMutation() {
  return useMutation({
    mutationFn: performLogin,
    gcTime: 0,
  })
}
