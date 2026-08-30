package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.shared.util.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Repositorio del reporte de facturacion (GET /services/report). Vive en {@code shared/repository/}
 * por la misma convencion que {@link ServiceStatsRepository}: cruza {@code operaciones.services}
 * con tablas de {@code public} y no tiene una entity 1:1 detras.
 *
 * <p><b>DOS consultas fijas, no una por fila.</b> Los conductores de refuerzo son la unica parte
 * del reporte con forma de N+1: resolverlos viaje por viaje seria una consulta por viaje. La semana
 * ya acota ese numero a decenas, asi que el motivo de peso NO es el costo sino que la segunda
 * consulta recibe los IDS que devolvio la primera, con lo cual los refuerzos corresponden a las
 * filas publicadas POR CONSTRUCCION (el porque, en el javadoc de {@link #findAdditionalDriversOf}).
 *
 * <p>Se descarto el LEFT JOIN unico que trae todo junto: multiplica las filas del reporte por sus
 * refuerzos y obliga a des-duplicar en Java el resto de las columnas, que es mas barato en viajes a
 * la base y mucho mas facil de romper.
 *
 * <p><b>El filtro nombra el estado que SI entra</b> y no excluye los que no. Es el mismo criterio
 * que los agregados de {@link ServiceStatsRepository}: con una lista blanca, un estado nuevo no se
 * cuela solo y no queda una clausula de exclusion que alguien pueda borrar. Hace falta aunque hoy
 * la aplicacion no tenga camino para que un cancelado tenga fecha de fin: la base no lo impide y el
 * cutover escribe por fuera de la aplicacion.
 *
 * <p>Las dos consultas caen sobre indices que {@code V007} creo para esto:
 * {@code idx_op_services_status_end (status, end_date_time)} —comentado literalmente
 * "reporte semanal"— y {@code idx_op_assign_service}.
 */
@ApplicationScoped
public class ServiceReportRepository {

    /**
     * La ventana se consulta SEMIABIERTA: {@code >= desde} y {@code < hasta}. La columna guarda
     * MICROsegundos, asi que un tope escrito como "23:59:59.999" del ultimo dia dejaria afuera los
     * ultimos 999 microsegundos. Es la misma decision que tomo el strip de indicadores.
     */
    private static final String RANGE_FILTER =
        "s.status = :status AND s.end_date_time >= :fromInclusive AND s.end_date_time < :toExclusive ";
    // ⚠️ Un solo usuario: la consulta de las FILAS. La de refuerzos filtra por ids a proposito, y el
    // nombre de esta constante se lee como si las dos la compartieran — que es exactamente la
    // suposicion que introducia el hueco de consistencia que se arranco de raiz.

    @Inject
    EntityManager entityManager;

    /**
     * Los viajes completados de la semana, ordenados por fecha de fin.
     *
     * <p>El desempate por {@code id} NO es cosmetico: dos viajes cerrados en el mismo microsegundo
     * dejarian el orden librado al motor, y un reporte que se imprime dos veces con las filas
     * cambiadas de lugar parece un reporte distinto.
     *
     * <p>Los dos JOIN contra el conductor son EXTERNOS a proposito. La columna del viaje es
     * nullable y el contrato declara {@code principalDriver} como requerido: la contradiccion la
     * resuelve el service, que rechaza ruidosamente la fila incompleta. Con un JOIN interno la fila
     * DESAPARECERIA del reporte, que para un documento de facturacion es el peor resultado posible.
     */
    public List<ServicesReportRow> findCompletedInRange(
            OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {
        Query query = entityManager.createNativeQuery(
            "SELECT s.id, s.code, c.name AS client_name, s.trip_scope, s.origin, s.destination, "
                + "s.start_date_time, s.end_date_time, s.price, cur.code AS currency_code, "
                + DriverRepository.FULL_NAME_EXPRESSION + " AS driver_name "
                + "FROM operaciones.services s "
                + "JOIN public.clients c ON c.id = s.client_id "
                + "JOIN public.currencies cur ON cur.id = s.currency_id "
                + "LEFT JOIN public.drivers d ON d.id = s.driver_id "
                + "LEFT JOIN public.workers w ON w.id = d.worker_id "
                + "WHERE " + RANGE_FILTER
                + "ORDER BY s.end_date_time, s.id", Tuple.class);
        setRangeParameters(query, fromInclusive, toExclusive);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        List<ServicesReportRow> reportRows = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            reportRows.add(new ServicesReportRow(
                ((Number) row.get(0)).longValue(),
                (String) row.get(1),
                (String) row.get(2),
                (String) row.get(3),
                (String) row.get(4),
                (String) row.get(5),
                // NO se castea directo: segun la version del driver la misma columna llega como
                // Instant, Timestamp u OffsetDateTime. Mismo helper que el resto del modulo.
                //
                // El inicio se pregunta por null ANTES de convertir: es la unica columna temporal
                // que puede LLEGAR null (el fin lo garantiza el filtro de la ventana), y el helper
                // compartido revienta con lo que no reconoce, null incluido. Sin esta guarda la fila
                // moria aca, una capa antes de `requireCompleteRow`, que es justo la que existe para
                // nombrar el viaje incompleto: el 500 salia igual pero SIN el codigo ni el detalle
                // del catalogo de errores, y sin la linea de log que dice CUAL es la fila mal
                // migrada.
                row.get(6) == null ? null : DateUtils.toOffsetDateTime(row.get(6)),
                // El fin NO puede ser null: la fila entra por el filtro de la ventana sobre esa columna.
                DateUtils.toOffsetDateTime(row.get(7)),
                (BigDecimal) row.get(8),
                (String) row.get(9),
                (String) row.get(10)));
        }
        return reportRows;
    }

    /**
     * Los conductores de REFUERZO de los viajes QUE SE VAN A PUBLICAR, en una sola consulta.
     *
     * <p><b>Alcance de la garantia:</b> lo que corresponde POR CONSTRUCCION es el CONJUNTO de ids,
     * no el contenido de cada lista. Bajo READ COMMITTED cada sentencia toma su propio snapshot, asi
     * que esta consulta ve los refuerzos que se sumaron despues de leer los viajes. Hoy eso solo
     * puede AGREGAR un relevo y nunca quitarlo, porque la tabla es append-only: no existe endpoint
     * que borre una asignacion. ⚠️ El dia que exista, el sintoma que este diseno cierra —un refuerzo
     * que desaparece en silencio de un documento de facturacion— vuelve por esta puerta.
     *
     * <p><b>Recibe los ids de la consulta de arriba y NO vuelve a aplicar la ventana.</b> Eso no es un
     * detalle de eficiencia: es lo que hace que los refuerzos correspondan a las filas publicadas
     * POR CONSTRUCCION. Con la ventana re-aplicada, las dos consultas miran instantes distintos —bajo
     * READ COMMITTED cada sentencia toma su propio snapshot, y {@code @Transactional} no lo cambia—
     * asi que bastaba con que alguien corrigiera la fecha de fin de un viaje entre una y otra para
     * que sus refuerzos no volvieran y la fila saliera con la lista vacia, en silencio, en un
     * documento de FACTURACION. Y el camino es alcanzable: {@code COMPLETED} es terminal para la
     * maquina de estados, pero la fila sigue siendo EDITABLE (solo el cancelado y el eliminado son
     * inmutables), y la edicion escribe esa columna.
     *
     * <p>Es el mismo argumento con el que los totales se calculan sobre las filas ya construidas y
     * no con una consulta aparte: lo que se publica junto tiene que salir de la misma foto.
     *
     * <p><b>El discriminador de "es conductor" es el JOIN INTERNO contra {@code public.drivers}</b>,
     * y no una clausula aparte: cada fila de refuerzo puede traer tracto, carreta y/o conductor
     * mezclados, y una fila sin conductor no encuentra pareja en ese join. Llego a escribirse ademas
     * un {@code WHERE a.driver_id IS NOT NULL} y se quito: era REDUNDANTE, ninguna prueba podia
     * distinguir su presencia de su ausencia —una mutacion que lo borraba sobrevivia la suite
     * entera— y una guarda que no se puede medir es una guarda que nadie sabe si funciona. Si algun
     * dia ese join se vuelve EXTERNO, la condicion vuelve a hacer falta.
     *
     * <p>El JOIN contra {@code workers} es EXTERNO aunque {@code drivers.worker_id} sea NOT NULL con
     * clave foranea, o sea que hoy el caso es inalcanzable. Se elige asi porque de las dos formas de
     * fallar, un refuerzo que DESAPARECE es peor que uno con el nombre en null: lo primero altera un
     * documento de facturacion sin dejar rastro. ⚠️ Nadie chequea ese null: el contrato declara
     * {@code name} requerido, asi que se publicaria igual y el cliente generado lo recibiria. Si esa
     * fila alguna vez puede faltar, la guarda de {@code requireCompleteRow} tiene que cubrirlo.
     *
     * <p>El {@code service_id} viaja en la fila para poder agrupar del lado de Java, y el orden
     * repite el del detalle ({@code assigned_at, id}) para que el mismo relevo se lea igual en los
     * dos lugares.
     *
     * <p>⚠️ El desempate por {@code a.id} NO lo distingue ninguna prueba: borrarlo deja la suite en
     * verde, incluso con dos refuerzos en el mismo instante y con la fila reescrita para invertir el
     * orden fisico (la tecnica que si funciona para las filas del reporte). Se conserva igual porque
     * es lo que hace que dos lecturas de la misma semana den el mismo documento cuando dos relevos
     * comparten instante, cosa que el cutover puede traer. Queda escrito para que nadie lo borre
     * creyendo que sobra, y para que nadie crea que hay una red que no existe.
     *
     * <p>⚠️ Y lo mismo con el {@code LEFT JOIN} contra {@code workers}: endurecerlo a {@code JOIN}
     * tambien sobrevive, porque {@code drivers.worker_id} es NOT NULL con clave foranea. Se conserva
     * externo por la misma razon por la que existe la advertencia de arriba: si esa fila alguna vez
     * puede faltar, es preferible un nombre en null que un refuerzo que desaparece.
     *
     * <p>⚠️ Lo mismo vale para {@code a.service_id} en ese {@code ORDER BY}: borrarlo tambien deja
     * la suite en verde, porque la pertenencia se rehace en Java con un mapa y no depende de que las
     * filas vengan agrupadas. Se conserva porque el resultado es identico y leerlo agrupado ayuda a
     * quien depure la consulta a mano, no porque haya un test que lo sostenga.
     */
    public List<ServiceAdditionalDriverRow> findAdditionalDriversOf(List<Long> serviceIds) {
        // Sin viajes no hay nada que preguntar, y ademas evita mandarle un arreglo vacio al motor.
        if (serviceIds.isEmpty()) {
            return List.of();
        }
        Query query = entityManager.createNativeQuery(
            "SELECT a.service_id, " + DriverRepository.FULL_NAME_EXPRESSION + " AS driver_name, "
                + "a.reason "
                + "FROM operaciones.service_assignments a "
                + "JOIN public.drivers d ON d.id = a.driver_id "
                + "LEFT JOIN public.workers w ON w.id = d.worker_id "
                + "WHERE a.service_id = ANY(:serviceIds) "
                + "ORDER BY a.service_id, a.assigned_at, a.id", Tuple.class);
        query.setParameter("serviceIds", serviceIds.toArray(new Long[0]));

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        List<ServiceAdditionalDriverRow> drivers = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            drivers.add(new ServiceAdditionalDriverRow(
                ((Number) row.get(0)).longValue(),
                (String) row.get(1),
                (String) row.get(2)));
        }
        return drivers;
    }

    /** El estado y los dos bordes de la ventana. Un solo llamador: la consulta de las filas. */
    private static void setRangeParameters(
            Query query, OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {
        query.setParameter("status", ServiceStatus.COMPLETED.name());
        query.setParameter("fromInclusive", fromInclusive);
        query.setParameter("toExclusive", toExclusive);
    }

    /**
     * Una fila cruda del reporte. El conductor principal puede venir NULL: la columna del viaje es
     * nullable y quien decide que hacer con eso es el service, no el repositorio.
     */
    public record ServicesReportRow(
        long serviceId,
        String code,
        String clientName,
        String tripScope,
        String origin,
        String destination,
        OffsetDateTime startDateTime,
        OffsetDateTime endDateTime,
        BigDecimal price,
        String currencyCode,
        String principalDriverName
    ) {
    }

    /** Un conductor de refuerzo, con el viaje al que pertenece para poder agrupar. */
    public record ServiceAdditionalDriverRow(
        long serviceId,
        String driverName,
        String reason
    ) {
    }
}
