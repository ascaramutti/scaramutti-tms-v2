// Query keys de react-query para el dominio de Auth.
// Centralizadas para que multiples lugares (AuthContext, interceptor, etc.)
// usen la misma key sin riesgo de typo.
export const currentUserQueryKey = ['auth', 'currentUser'] as const
