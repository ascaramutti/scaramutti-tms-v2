import { useMutation } from '@tanstack/react-query'
import { changePassword, type ChangePasswordRequest } from '../../../api'

// `throwOnError: true` hace que axios rechace la promesa en 4xx, asi react-query
// dispara `onError` con un AxiosError que el caller puede parsear (Problem body).
async function performChangePassword(body: ChangePasswordRequest): Promise<void> {
  await changePassword({ body, throwOnError: true })
}

// gcTime 0: la caché de mutaciones guarda las variables, y acá son las dos contraseñas. Así no
// siguen en memoria una vez que la pantalla se desmonta.
export function useChangePasswordMutation() {
  return useMutation({
    mutationFn: performChangePassword,
    gcTime: 0,
  })
}
