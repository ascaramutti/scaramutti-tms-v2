package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.service.cmd.ListServicesQuery;
import com.scaramutti.tms.shared.entity.Service;
import com.scaramutti.tms.shared.util.DateUtils;
import com.scaramutti.tms.shared.util.MultiWordSearch;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repositorio del servicio de transporte. El id es {@code Long} porque la tabla usa
 * {@code BIGSERIAL} (el resto de los modulos usa SERIAL/Integer).
 */
@ApplicationScoped
public class ServiceRepository implements PanacheRepositoryBase<Service, Long> {

    @Inject
    EntityManager entityManager;

    /**
     * Una pagina del listado, con el cliente y la moneda ya resueltos por JOIN: una sola
     * consulta para toda la pagina, sin una lectura extra por fila. Orden fijo por fecha de
     * creacion descendente (el id desempata los que caen en el mismo instante).
     */
    public List<ServiceListRow> searchPaged(ListServicesQuery query) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = "SELECT s.id, s.code, s.origin, s.destination, s.tentative_date, s.trip_scope, "
            + "s.status, s.price, cur.code AS currency_code, s.created_at, "
            + "c.id AS client_id, c.name AS client_name, c.ruc, c.phone, c.contact_name, "
            + "s.driver_id, " + DriverRepository.FULL_NAME_EXPRESSION + " AS driver_name, "
            + "s.tractor_id, tra.plate AS tractor_plate "
            + fromAndWhere(query, params, ASSIGNED_RESOURCE_JOINS)
            + " ORDER BY s.created_at DESC, s.id DESC LIMIT :pageSize OFFSET :pageOffset";

        Query nativeQuery = entityManager.createNativeQuery(sql, Tuple.class);
        params.forEach(nativeQuery::setParameter);
        nativeQuery.setParameter("pageSize", query.size());
        nativeQuery.setParameter("pageOffset", (long) query.page() * query.size());

        @SuppressWarnings("unchecked")
        List<Tuple> rows = nativeQuery.getResultList();
        // El orden de lectura sigue al del SELECT de arriba: si se toca uno, se toca el otro.
        return rows.stream().map(row -> new ServiceListRow(
            ((Number) row.get(0)).longValue(),
            (String) row.get(1),
            (String) row.get(2),
            (String) row.get(3),
            DateUtils.toLocalDate(row.get(4)),
            (String) row.get(5),
            (String) row.get(6),
            (BigDecimal) row.get(7),
            (String) row.get(8),
            // Los tipos temporales pasan por los helpers de la casa: segun la version del
            // driver la misma columna llega como Instant, Timestamp u OffsetDateTime.
            DateUtils.toOffsetDateTime(row.get(9)),
            ((Number) row.get(10)).intValue(),
            (String) row.get(11),
            (String) row.get(12),
            (String) row.get(13),
            (String) row.get(14),
            toInteger(row.get(15)),
            (String) row.get(16),
            toInteger(row.get(17)),
            (String) row.get(18)
        )).toList();
    }

    /**
     * Uniones para traer el conductor y el tracto ya asignados. Son EXTERNAS: un viaje pendiente
     * de asignacion no tiene ninguno, y con uniones internas desapareceria del listado — que es
     * justo el estado en el que mas se lo busca.
     *
     * <p>La carreta no viaja en la fila del listado: es opcional y la tabla no la muestra. Vive
     * en el detalle.
     */
    private static final String ASSIGNED_RESOURCE_JOINS =
        "LEFT JOIN public.drivers d ON d.id = s.driver_id "
            + "LEFT JOIN public.workers w ON w.id = d.worker_id "
            + "LEFT JOIN public.tractors tra ON tra.id = s.tractor_id ";

    /** Total de servicios que matchean los filtros. Reusa el MISMO FROM+WHERE que la pagina. */
    public long countSearch(ListServicesQuery query) {
        Map<String, Object> params = new LinkedHashMap<>();
        // Sin las uniones de recursos: son externas y no cambian cuantas filas hay, asi que
        // contarlas con ellas seria pagar tres uniones por un numero que no depende de ellas.
        Query nativeQuery = entityManager.createNativeQuery("SELECT COUNT(*) " + fromAndWhere(query, params, ""));
        params.forEach(nativeQuery::setParameter);
        return ((Number) nativeQuery.getSingleResult()).longValue();
    }

    /**
     * FROM + WHERE compartidos por la pagina y su total, para que el filtro no pueda divergir. El
     * JOIN al cliente y a la moneda es INTERNO a proposito: las dos columnas son NOT NULL con FK,
     * asi que no puede perder filas, y de paso una FK huerfana se notaria en vez de esconderse.
     *
     * <p>{@code extraJoins} lo usa solo la pagina, para traer columnas que el total no necesita.
     * <b>Cada union extra tiene que dar A LO SUMO UNA fila por servicio</b> (union externa contra
     * una clave primaria o una columna unica). Ser externa evita PERDER filas; no evita
     * MULTIPLICARLAS, y una union uno-a-muchos —los refuerzos de un viaje, por ejemplo— haria
     * aparecer el mismo viaje varias veces en la pagina, comiendose los lugares del limite,
     * mientras el total sigue contando viajes. La paginacion se desfasaria sin que nada falle.
     * Un dato uno-a-muchos va por subconsulta agregada, no por union.
     */
    private String fromAndWhere(ListServicesQuery query, Map<String, Object> params, String extraJoins) {
        List<String> conditions = new ArrayList<>();
        if (query.q() != null) {
            // OJO: este OR cruza columnas de services y de clients, y eso hace que Postgres
            // resuelva la busqueda como filtro del join, sin usar los indices trigram de la
            // tabla (medido con EXPLAIN). Medido tambien el costo: 43 ms sobre 30.000 filas, o
            // sea unos pocos milisegundos al volumen real del negocio (unos 2.000 viajes al
            // anio). Si algun dia deja de alcanzar, la salida es partir la busqueda en dos
            // ramas, no agregar mas indices.
            conditions.addAll(MultiWordSearch.conditions(
                query.q(),
                List.of("s.code", "c.name", "c.ruc", "s.origin", "s.destination"),
                "qTok", params));
        }
        if (query.status() != null) {
            conditions.add("s.status = :status");
            params.put("status", query.status().name());
        } else {
            // Los eliminados son registros que nunca debieron existir: no ensucian el listado
            // salvo que alguien los pida por su nombre.
            conditions.add("s.status <> :excludedStatus");
            params.put("excludedStatus", ServiceStatus.DELETED.name());
        }
        if (query.clientId() != null) {
            conditions.add("s.client_id = :clientId");
            params.put("clientId", query.clientId());
        }
        if (query.dateFrom() != null) {
            conditions.add("s.tentative_date >= :dateFrom");
            params.put("dateFrom", query.dateFrom());
        }
        if (query.dateTo() != null) {
            conditions.add("s.tentative_date <= :dateTo");
            params.put("dateTo", query.dateTo());
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        return "FROM operaciones.services s "
            + "JOIN public.clients c ON c.id = s.client_id "
            + "JOIN public.currencies cur ON cur.id = s.currency_id "
            + extraJoins
            + where;
    }

    /**
     * Fila del listado. El estado y el ambito viajan como el String de la columna y el mapper los
     * traduce al enum, igual que en el detalle.
     */
    public record ServiceListRow(
        Long id,
        String code,
        String origin,
        String destination,
        LocalDate tentativeDate,
        String tripScope,
        String status,
        BigDecimal price,
        String currencyCode,
        OffsetDateTime createdAt,
        Integer clientId,
        String clientName,
        String clientRuc,
        String clientPhone,
        String clientContactName,
        Integer driverId,
        String driverFullName,
        Integer tractorId,
        String tractorPlate
    ) {}

    /**
     * Los recursos asignados de UN viaje, con el nombre del conductor y las placas ya resueltos.
     *
     * <p>Una sola consulta con tres uniones EXTERNAS: los tres son opcionales (un viaje pendiente
     * de asignacion no tiene ninguno, y la carreta puede faltar siempre), asi que con uniones
     * internas el viaje entero desapareceria de la respuesta por no tener recursos.
     *
     * <p>El nombre del conductor se arma con la MISMA expresion que el catalogo
     * ({@code DriverRepository}): dos formas distintas de componer el nombre harian que la misma
     * persona se llame distinto segun por donde se la mire.
     */
    public ServiceAssignedResourcesRow findAssignedResources(long serviceId) {
        Query query = entityManager.createNativeQuery(
            "SELECT s.driver_id, " + DriverRepository.FULL_NAME_EXPRESSION + " AS driver_name, "
                + "s.tractor_id, tra.plate AS tractor_plate, s.trailer_id, tri.plate AS trailer_plate "
                + "FROM operaciones.services s "
                + "LEFT JOIN public.drivers d ON d.id = s.driver_id "
                + "LEFT JOIN public.workers w ON w.id = d.worker_id "
                + "LEFT JOIN public.tractors tra ON tra.id = s.tractor_id "
                + "LEFT JOIN public.trailers tri ON tri.id = s.trailer_id "
                + "WHERE s.id = :serviceId", Tuple.class);
        query.setParameter("serviceId", serviceId);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        if (rows.isEmpty()) {
            // La fila existe: quien llama ya la leyo. Solo puede faltar si alguien la borro en
            // duro entre las dos lecturas, y ahi el viaje ya no tiene recursos que mostrar.
            return ServiceAssignedResourcesRow.EMPTY;
        }
        Tuple row = rows.get(0);
        return new ServiceAssignedResourcesRow(
            toInteger(row.get(0)), (String) row.get(1),
            toInteger(row.get(2)), (String) row.get(3),
            toInteger(row.get(4)), (String) row.get(5));
    }

    /**
     * Los recursos asignados de un viaje. Los ids pueden ser null (el viaje todavia no los tiene,
     * o no lleva carreta); cuando un id viene, su etiqueta viene con el.
     */
    public record ServiceAssignedResourcesRow(
        Integer driverId,
        String driverFullName,
        Integer tractorId,
        String tractorPlate,
        Integer trailerId,
        String trailerPlate
    ) {
        static final ServiceAssignedResourcesRow EMPTY =
            new ServiceAssignedResourcesRow(null, null, null, null, null, null);
    }

    /** Las columnas de id llegan como {@code Number} de ancho variable segun el driver. */
    private static Integer toInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    /**
     * Reserva el proximo id de la secuencia del BIGSERIAL. Se pide ANTES del INSERT porque el
     * codigo del viaje se deriva del id: con el id en la mano, la fila entra completa de una
     * sola vez, sin un UPDATE posterior para completar el codigo.
     */
    public Long nextId() {
        return ((Number) entityManager
            .createNativeQuery("SELECT nextval('operaciones.services_id_seq')")
            .getSingleResult()).longValue();
    }

    /**
     * Adquiere un advisory lock por (usuario, cliente) dentro de la transaccion actual, que se
     * libera solo al commit o al rollback. Va ANTES del chequeo anti-duplicado para cerrar la
     * ventana entre "consulto" y "inserto": dos altas simultaneas del mismo usuario para el
     * mismo cliente quedan serializadas aca, asi la segunda ve persistida a la primera.
     *
     * <p>Mismo mecanismo que el alta de cotizaciones, y a proposito con la misma forma de clave
     * (usuario, cliente): las dos altas comparten espacio de locks, asi que un alta de
     * cotizacion y una de servicio del mismo usuario para el mismo cliente se esperan entre si.
     * Es benigno: el alta de servicio toma UN solo lock y no espera por ningun otro, asi que no
     * puede cerrar un ciclo (la de cotizaciones toma dos, el anti-duplicado y el del anio), y las
     * ventanas son de milisegundos. Separar los espacios exigiria hashear el modulo en la clave
     * sin ganar nada hoy.
     */
    public void acquireAntiDuplicateLock(Integer createdBy, Integer clientId) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:k1, :k2)")
            .setParameter("k1", createdBy)
            .setParameter("k2", clientId)
            .getSingleResult();
    }

    /**
     * Servicios que el mismo usuario acaba de registrar para el mismo cliente y la misma ruta
     * dentro de la ventana dada. Alimenta la guarda anti doble-click: si devuelve algo, el alta
     * entrante es un reenvio del formulario, no un viaje nuevo.
     *
     * <p>La ruta se compara tal cual quedo guardada: la guarda apunta al mismo formulario
     * enviado dos veces, no a dos viajes parecidos escritos distinto.
     */
    public List<Service> findRecentByCreatedByAndClientAndRoute(
        Integer createdBy, Integer clientId, String origin, String destination, int secondsWindow
    ) {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(secondsWindow);
        return list("createdBy = ?1 AND clientId = ?2 AND origin = ?3 AND destination = ?4 AND createdAt >= ?5",
            createdBy, clientId, origin, destination, cutoff);
    }
}
