# Changelog

Todos los cambios notables de Scaramutti TMS v2, por versión. El formato sigue
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones,
[SemVer](https://semver.org/lang/es/): `feat` sube el minor, `fix` el patch, `BREAKING CHANGE` el
major. Cada sección se escribe desde los commits convencionales del rango
`<tag anterior>..<tag>`; el detalle de cada cambio está en su PR.

Las versiones anteriores a 2.5.0 se etiquetaron sin este archivo; su resumen sale del mensaje de
cada tag anotado.

## [2.5.0] - sin publicar

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
