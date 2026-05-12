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

export function useLoginMutation() {
  return useMutation({
    mutationFn: performLogin,
  })
}
