import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist', 'coverage', 'src/api/**']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      // La primera línea de defensa contra volver a escribir un color a mano: avisa en el
      // editor, antes de que el color llegue a un commit. La segunda vive en la suite
      // (`src/shared/theme/no-raw-colors.test.ts`), da el número exacto y no se puede
      // desactivar desde acá.
      //
      // Dos límites conocidos, y por eso son dos capas y no una: esto NO ve una cadena
      // construida (una plantilla con una variable adentro se le escapa) y solo mira
      // literales, así que un color que llegue por una constante importada tampoco. La
      // prueba ve el color escrito ENTERO aunque esté dentro de una plantilla, porque lee
      // el texto de los archivos; **un color armado por partes se le escapa a las dos**, y
      // eso lo midió la revisión del PR del modo oscuro.
      //
      // Y un límite de reparto: esta regla solo corre sobre TypeScript, mientras que Tailwind
      // publica una clase desde cualquier archivo del proyecto que no esté excluido, el
      // documento de entrada y la configuración incluidos. Esa mitad la cubre la prueba, que
      // toma su corpus del mismo escáner que el compilador. Una versión anterior de esta nota
      // repartía al revés y afirmaba que acá se cazaba lo que la prueba perdía detrás de una
      // URL: era falso en los dos sentidos, y la revisión lo midió.
      //
      // Van dos selectores porque el blanco y el negro no llevan número, que es el agujero
      // que el documento de diseño le señala a la primera versión de esta regla.
      'no-restricted-syntax': [
        'error',
        {
          selector:
            'Literal[value=/\\b(bg|text|border|ring|divide|placeholder|outline|accent|caret|decoration|fill|stroke|shadow|from|to|via)-(slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-[0-9]{2,3}\\b/]',
          message:
            'Usá un token del tema y no un color crudo de Tailwind. Los tokens están en src/index.css, y hay uno por función: superficie, texto, borde, acento, estado.',
        },
        {
          selector:
            'Literal[value=/\\b(bg|text|border|ring|divide|placeholder|outline|accent|caret|decoration|fill|stroke|shadow|from|to|via)-(white|black)\\b/]',
          message:
            'Usá un token del tema y no blanco o negro directo: el blanco de una superficie y el blanco de un texto sobre relleno son dos roles distintos, y en modo oscuro valen cosas distintas.',
        },
      ],
    },
  },
  {
    // La carpeta que MIDE el tema habla de clases por oficio: sus patrones y sus notas las
    // nombran para poder buscarlas. Está fuera del escaneo de Tailwind, así que nombrarlas
    // acá no publica una sola regla.
    files: ['src/shared/theme/**'],
    rules: { 'no-restricted-syntax': 'off' },
  },
])
