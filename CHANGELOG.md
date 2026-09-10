# Changelog

Todos los cambios notables de Scaramutti TMS v2, por versión. El formato sigue
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones,
[SemVer](https://semver.org/lang/es/): `feat` sube el minor, `fix` el patch, `BREAKING CHANGE` el
major. Cada sección se escribe desde los commits convencionales del rango
`<tag anterior>..<tag>`; el detalle de cada cambio está en su PR.

Las versiones anteriores a 2.5.0 se etiquetaron sin este archivo; su resumen sale del mensaje de
cada tag anotado.

## [Sin publicar]

### Security

- Seis endpoints exigían sesión solo por la política por ruta del servidor HTTP: los catálogos de
  monedas, términos de pago, condiciones de cotización y tipos de servicio, y los listados de tipos
  de carga y de clientes. Otros dos, el perfil propio y el cambio de contraseña, la exigían recién
  dentro del método. Los ocho la exigen ahora antes de entrar. Esa política se evalúa sobre la URL
  tal como llega y hay avisos publicados de rutas que la esquivan escribiéndola torcida; la
  comprobación del código no depende de cómo se escriba la ruta. No cambia quién puede entrar: los
  mismos roles que antes, y el login y la renovación del token siguen siendo públicos (#203).

## [2.6.1] - 2026-09-09

Las dependencias al día y la infraestructura del ciclo: PRs #187 a #200. Quarkus en su último
parche de la 3.15, los menores del frontend con el cliente de la API regenerado, y los workflows
del ciclo con gitleaks, Dependabot y las acciones en Node 24. Sin migraciones, sin cambios de
contrato.

### Changed

- Las dependencias de ejecución suben dentro de su misma versión mayor: `react` y `react-dom` de
  19.2.6 a 19.2.8, `@tanstack/react-query` de 5.100.10 a 5.102.8, `react-hook-form` de 7.75.0 a
  7.87.0, `@hookform/resolvers` de 5.2.2 a 5.9.1, `zod` de 4.4.3 a 4.5.4, `lucide-react` de 1.14.0
  a 1.43.0 y `sonner` de 2.0.7 a 2.0.8. La auditoría de dependencias de ejecución sigue sin avisos
  (#200).
- Las herramientas de desarrollo (linter, empaquetador, motor de estilos, utilidades de prueba y
  generador del cliente de la API) suben en el mismo grupo. El generador nuevo vuelve a emitir el
  cliente commiteado: la salida anota los tipos de retorno que antes se inferían y no mueve ninguna
  URL, ningún esquema de seguridad ni ningún tipo del contrato (#200).

## [2.6.0] - 2026-09-08

La SPA se muda a la raíz del dominio, con las dependencias de ejecución por encima de sus avisos de
seguridad y con cabeceras de caché en las respuestas del frontend: PRs #180 a #184. Solo frontend y
su nginx; sin migraciones, sin cambios de backend ni de contrato.

### Changed

- La SPA se sirve desde la raíz del dominio. El módulo de cotizaciones conserva sus URL; login,
  cuenta, almacén y operaciones pasan a `/login`, `/cuenta/cambiar-contrasena`, `/almacen` y
  `/operaciones`. Las URL viejas no se redirigen: terminan en la pantalla principal de cada rol,
  igual que cualquier URL inexistente (#183).

### Fixed

- Al entrar por un enlace directo, después del login se respeta ese destino en vez de mandar
  siempre a la pantalla principal; si el rol no puede abrirlo, cae en la suya. Un despachador que
  abría un enlace a una cotización veía "Sin acceso" (#183).
- El frontend manda `Cache-Control` en sus respuestas: `no-cache` en el documento, para que cada
  despliegue llegue sin recarga forzada, y un año con `immutable` en los assets, que llevan un hash
  del contenido en el nombre. Hasta la 2.5.0 no mandaba ninguna (#181).

### Security

- `axios` y `react-router-dom` actualizados por encima de sus avisos de seguridad publicados: axios
  de 1.16.0 a 1.20.0 y react-router-dom de 7.15.0 a 7.18.3, que arrastra `form-data` a 4.0.6. La
  auditoría de dependencias de ejecución queda sin avisos (#180).

## [2.5.0] - 2026-09-05

Serie del tema del frontend: PRs #168 a #177. Solo frontend; sin migraciones, sin cambios de
backend ni de contrato.

### Added

- Tokens semánticos del tema: los colores se nombran por función y no por tono, de modo que el
  modo oscuro los redefine sin tocar una clase del marcado (#168).
- Modo oscuro detrás de un interruptor en el pie de la barra lateral. Manda la elección del usuario
  y después la del sistema; un script sincrónico escribe el atributo antes de que React monte para
  evitar el destello al recargar (#175).
- `Badge` con sus siete variantes en tokens y `KpiTile` extraído de los dos tableros (#172).

### Changed

- `Button`, `Card` y `Alert` pasan a componentes compartidos escritos con los tokens del tema;
  los controles de formulario, a un molde compartido por piezas. La forma no cambia (#169, #170,
  #171).
- Todas las clases de color escritas a mano del frontend pasan a tokens: no queda una clase de
  color suelta, salvo las que tienen su motivo escrito (#173, #174).

### Fixed

- El foco de teclado se ve en todos los controles que lo reciben; la fila clickeable de las tablas
  lleva contorno propio, así que un listado se puede recorrer con el tabulador (#176).
- El tema claro llega al mínimo de contraste de la norma: el texto de sugerencia y el borde de los
  campos pasan de 2.63 y 1.49 a 4.76 (#177).

## [2.4.1] - 2026-08-30

### Changed

- El frontend sirve la API: su nginx pasa a escribirse como plantilla, suma el proxy a `/api/v1/`
  y los redirects que resolvía el gateway, que se retira (#166).

## [2.4.0] - 2026-08-29

### Added

- Módulo de operaciones: el control de viajes deja v1 y pasa a vivir en la aplicación. Doce
  endpoints y ocho pantallas: listado con indicadores, alta, detalle con bitácora, edición con
  justificación, asignación de recursos con conflicto forzable, refuerzos y su baja, y las cinco
  transiciones de estado. El despacho pasa a trabajar dentro de la aplicación y ventas gana acceso
  al detalle y a la edición.
- Migraciones V007, V008 y V009: el schema de operaciones, sin tocar los catálogos compartidos.

## [2.3.0] - 2026-07-26

### Added

- Módulo de almacén, backend y frontend.

## [2.2.0] - 2026-06-20

### Added

- Condiciones seleccionables en la cotización.

## [2.1.1] - 2026-06-18

### Fixed

- La edición se deshabilita en las cotizaciones en estado terminal: botón, tooltip y guarda en la
  página de edición.

## [2.1.0] - 2026-06-17

### Added

- Gestión de estados de la cotización: máquina de estados, endpoint, job de vencimiento y UI.

## [2.0.0] - 2026-06-16

### Added

- Notas de observación en la cotización: nota al cliente y nota interna.
