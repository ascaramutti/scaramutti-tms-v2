import { defineConfig } from '@hey-api/openapi-ts'

// Genera el cliente TypeScript desde la spec runtime del backend.
// Se commitea el output (src/api/) — el CI no necesita regenerar.
// Cuando cambie el contrato, correr `npm run generate-api` localmente.
export default defineConfig({
  input: '../backend/src/main/resources/META-INF/openapi.yaml',
  output: {
    path: 'src/api',
  },
  plugins: [
    '@hey-api/client-axios',
    '@hey-api/typescript',
    '@hey-api/sdk',
  ],
})
