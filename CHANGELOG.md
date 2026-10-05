# Changelog

Todos los cambios notables de Scaramutti TMS v2, por versión. El formato sigue
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones,
[SemVer](https://semver.org/lang/es/): `feat` sube el minor, `fix` el patch, `BREAKING CHANGE` el
major. Cada sección se escribe desde los commits convencionales del rango
`<tag anterior>..<tag>`; el detalle de cada cambio está en su PR.

Las versiones anteriores a 2.5.0 se etiquetaron sin este archivo; su resumen sale del mensaje de
cada tag anotado.

## [Unreleased]

El mantenimiento de trabajadores completo, del backend a las pantallas; el maestro de clientes con
pantallas propias; la alerta de reasignación de viajes; y las dependencias al día: PRs #214 a #254.
Dos migraciones, V010 y V011. El contrato suma nueve operaciones, el filtro `isAssignable` de
conductores y la marca `needsReassignment` de los viajes; contra 2.7.1 no retira ningún campo.

### Added

- Padrón de trabajadores en el grupo Administración del menú, para el admin, las dos gerencias y
  finanzas: búsqueda por nombre, apellido o documento con filtro de estado (#242),
  ficha con licencia de conducir y autoría (#244), alta (#245), edición con motivo obligatorio al
  cambiar el número de documento (#248), y desactivar y reactivar desde la ficha con una
  confirmación que dice qué arrastra la baja: usuario, licencia y viajes pendientes (#249, #251).
  Cada acción se ofrece solo sobre cargos de nivel menor al de la sesión; el admin, siempre.
- Endpoints del padrón: `GET /workers/{id}`, `POST /workers`, `PUT /workers/{id}`,
  `POST /workers/{id}/deactivate` y `POST /workers/{id}/reactivate`, con los errores `WRK-001` a
  `WRK-013`, y los catálogos `GET /roles` y `GET /document-types` (#222 a #226). La baja apaga en
  la misma transacción la ficha de conductor y la cuenta del sistema; la reactivación enciende la
  ficha si el cargo la lleva y nunca la cuenta. Cada cambio deja su fila de auditoría.
- Maestro de clientes con pantallas de búsqueda, ficha y edición (#219) y sus endpoints
  `GET /clients/{id}` y `PUT /clients/{id}` (#217).
- Alerta de reasignación de viajes: el listado y el detalle traen `needsReassignment`, verdadera
  cuando un viaje pendiente de inicio o en ruta tiene un conductor, principal o de refuerzo, que ya
  no se puede asignar (#239); el detalle trae además `driverNeedsReassignment` en el conductor
  principal y en cada refuerzo (#232). La lista marca esos viajes con un ícono de advertencia
  (#240) y el detalle dice cuál conductor y por qué (#238).
- `GET /drivers` suma el campo y el filtro `isAssignable`: ficha encendida, trabajador activo y
  cargo de conductor. El selector de operaciones ofrece solo esos (#233).
- Migraciones V010 y V011. V010 pasa el cargo del trabajador a ser su rol, con nivel, si inicia
  sesión y si lleva ficha de conductor, y suma cuatro roles que no inician sesión, la fecha de
  ingreso, la autoría y la tabla de auditoría de trabajadores, y fija el nombre visible de los
  roles existentes. La columna vieja del cargo queda sin lectores para que la versión anterior
  pueda volver. Aborta con la lista de cargos que no tengan equivalente en vez de inventarles uno.
  V011 firma con el usuario `admin` a los trabajadores cargados antes de la auditoría; sin ese
  usuario no hace nada (#222, #243).

### Changed

- `GET /drivers` devuelve solo fichas de trabajadores con cargo de conductor, con o sin filtro de
  vigencia; el escolta y el ayudante con licencia ya no aparecen. La asignación de recursos y el
  refuerzo rechazan otro cargo con `400 OPS-011`, y la ficha de un trabajador dado de baja con el
  mismo 400 que una ficha apagada. El tablero cuenta a los conductores con ese criterio (#231,
  #233).
- La búsqueda de `GET /workers` mira también el número de documento, solo para los cuatro roles
  que mantienen el padrón; para almacén sigue siendo por nombre y apellido (#222).
- Nombre y apellido de un trabajador aceptan al menos una letra latina, más espacios, apóstrofo y
  guion; lo demás responde `400 COM-001` en el campo. Se guardan normalizados a NFC (#246).
- El cargo que muestran la firma del PDF de cotización, el pie del menú y la sesión sale del rol
  del trabajador, con el nombre fijo de cada rol, y no del texto libre que tenía cada uno: un cargo
  escrito en femenino pasa a la forma del rol (#222).
- El ítem Clientes del menú pasa al grupo Administración y ventas deja de verlo (#219).
- Parámetro nuevo `app.workers.edit-lock-timeout-ms` (380 ms): el backend no arranca si las once
  esperas de una edición no entran bajo la espera del pool de conexiones (#230).
- El backend pasa de Quarkus 3.33.3.2 a 3.33.4, con Hibernate ORM de 7.2.19 a 7.2.25, y Mockito
  de 5.23 a 5.24 (#252). El frontend pasa React de 19.2.8 a 19.3.0, vitest de 3.2.4 a 5.0.3 (pide
  Node 22) y jest-dom de 6.9.1 a 7.0.1, más las menores y parches agrupados (#214, #216, #218,
  #227, #253).

### Security

- Toda escritura del padrón relee a quien actúa en la misma transacción: cuenta activa, uno de los
  cuatro roles de escritura y su trabajador activo; si falta algo, `403 COM-003`. Un token con un
  rol viejo, o de alguien dado de baja, ya no escribe aunque siga vigente (#228). Las escrituras
  toman la fila de quien actúa: dos administradores que se bajan el cargo o se desactivan entre sí
  a la vez ya no pueden dejar la empresa sin administradores; una espera agotada sale como
  `409 WRK-013` (#229, #230).
- Quarkus 3.33.4 trae arreglos de seguridad, entre ellos el de Hibernate ORM y el de inyección de
  plantillas en Qute; la plantilla del PDF de cotizaciones no usa lo que se retira (#252).
- Se cierran todas las alertas de Dependabot del frontend, todas de herramientas de desarrollo:
  las de vitest y vite, incluida la única crítica (#218), la de esbuild (#227) y las de los
  paquetes transitivos (#254). `npm audit` queda en cero.

## [2.7.1] - 2026-09-12

La zona del negocio en todos los relojes: PRs #208 a #211. El "hoy" de los formularios y el año
del código de cotización salen del día calendario de America/Lima, no de la zona del navegador ni
de UTC; toda lectura de "ahora" del backend pasa por una fuente única, el helper del día de Lima
pasa a los utilitarios compartidos del frontend y la suite del backend corre con el reloj fijo en
UTC. Sin migraciones; el contrato cambia solo en cuatro descripciones.

### Fixed

- El año del código de cotización (`YYYY-NNNNN`) sale del día calendario de Lima. Entre las 19:00
  y la medianoche del 31 de diciembre, UTC ya está en el año siguiente, así que la primera
  cotización de esa noche salía numerada con un año que todavía no empezó para quien la emite, y
  además se llevaba el `00001` del año nuevo (#209).
- Los formularios de almacén y el asistente de cotizaciones calculan "hoy" en Lima y no en la zona
  del navegador: el valor propuesto de la fecha, el tope de los inputs y la regla de fecha
  tentativa no pasada. Se veía solo desde otra zona: entre las 19:00 y la medianoche de Perú
  proponían el día siguiente (#211).

### Changed

- Los sellados de creación y las ventanas anti doble clic del backend leen "ahora" desde la misma
  fuente, truncada a microsegundos como la columna que los guarda; un test recorre el código de
  producción y falla si vuelve un reloj fuera de ella. Cuatro descripciones del contrato dicen
  "día calendario en America/Lima" en vez de "UTC-5", y la del alta de cotización nombra el año de
  Lima; el cliente generado se regenera con ellas, sin cambio de tipos ni de URLs (#209).
- La hora del kardex se formatea con el ciclo de 24 horas explícito en vez de dejar que el locale
  decida si la medianoche es `00` o `24`; con el motor actual el texto es el mismo (#211).
- La suite del backend fija el reloj de su JVM en UTC en vez de heredarlo de la máquina que la
  corre, y un test avisa si esa línea del `pom.xml` desaparece (#208).

## [2.7.0] - 2026-09-10

Quarkus en la línea 3.33 con soporte hasta marzo de 2027, que cierra de raíz el aviso de
autorización por ruta, y las guardas de autenticación en el código de los ocho endpoints que
dependían solo de la política por ruta: PRs #203 a #205. Sin migraciones. Un cambio de contrato en
un rincón: el parámetro de búsqueda vacío equivale a omitirlo.

### Security

- Seis endpoints exigían sesión solo por la política por ruta del servidor HTTP: los catálogos de
  monedas, términos de pago, condiciones de cotización y tipos de servicio, y los listados de tipos
  de carga y de clientes. Otros dos, el perfil propio y el cambio de contraseña, la exigían recién
  dentro del método. Los ocho la exigen ahora antes de entrar. Esa política se evalúa sobre la URL
  tal como llega y hay avisos publicados de rutas que la esquivan escribiéndola torcida; la
  comprobación del código no depende de cómo se escriba la ruta. No cambia quién puede entrar: los
  mismos roles que antes, y el login y la renovación del token siguen siendo públicos (#203).
- El backend pasa a la línea 3.33 de Quarkus, la de soporte largo vigente, y con ella a Hibernate
  7. Incluye el arreglo del aviso de autorización por ruta de junio de 2026, que la línea 3.15 no
  recibió: ese aviso describe caminos escritos con punto y coma o con barras codificadas que dejan
  de coincidir con la política que protege `/api/v1/*`. Esa política es la primera de las dos capas
  que exigen sesión; la segunda es la del código, de la entrada anterior (#204).

### Changed

- En siete de los ocho buscadores, mandar `q` vacío (`?q=`) pasa a significar lo mismo que no
  mandarlo: devuelve el listado sin filtrar en vez de rechazar con 400. Es una consecuencia del
  salto de plataforma, que ahora entrega un parámetro vacío como ausente. El octavo, el listado de
  servicios, ya se comportaba así porque su recurso colapsa el texto vacío antes de usarlo. El
  mínimo de tres caracteres no cambia en ninguno: con uno o dos sigue siendo 400 (#204).

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
