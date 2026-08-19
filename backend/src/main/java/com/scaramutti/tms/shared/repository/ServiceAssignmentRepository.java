package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.operations.model.ServiceResourceKind;
import com.scaramutti.tms.shared.entity.ServiceAssignment;
import com.scaramutti.tms.shared.util.DateUtils;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Locale;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Los REFUERZOS de un viaje ({@code operaciones.service_assignments}): los recursos que se le
 * suman cuando ya esta en ruta.
 */
@ApplicationScoped
public class ServiceAssignmentRepository implements PanacheRepositoryBase<ServiceAssignment, Long> {

    /** La columna de cada recurso, que se llama IGUAL en las dos tablas donde puede estar. */
    private static final String DRIVER_COLUMN = "driver_id";
    private static final String TRACTOR_COLUMN = "tractor_id";
    private static final String TRAILER_COLUMN = "trailer_id";

    @Inject
    EntityManager entityManager;

    /**
     * El SELECT de los refuerzos de un viaje, con el nombre del conductor y las placas ya resueltos.
     *
     * <p>Se comparte entre el listado y la busqueda de UNO porque esos valores tienen que resolverse
     * con la MISMA expresion en los dos: si el listado y la baja los armaran por separado, la linea
     * de bitacora de la baja podria nombrar al mismo conductor distinto que el resto del modulo. El
     * nombre sale de la MISMA expresion que el catalogo ({@link DriverRepository}), por el mismo
     * motivo que en {@link ServiceRepository}.
     *
     * <p>Cuatro uniones EXTERNAS, una por cada tabla que se cruza (los tres RECURSOS mas el
     * trabajador detras del conductor): cada fila es un PEDIDO y puede traer uno, dos o los tres,
     * asi que con uniones internas desapareceria justamente la mayoria, que son las que no los traen
     * todos.
     */
    private static final String ADDITIONAL_RESOURCE_SELECT =
        "SELECT a.id, a.driver_id, " + DriverRepository.FULL_NAME_EXPRESSION + " AS driver_name, "
            + "a.tractor_id, tra.plate AS tractor_plate, a.trailer_id, tri.plate AS trailer_plate, "
            + "a.reason, a.assigned_by, a.assigned_at "
            + "FROM operaciones.service_assignments a "
            + "LEFT JOIN public.drivers d ON d.id = a.driver_id "
            + "LEFT JOIN public.workers w ON w.id = d.worker_id "
            + "LEFT JOIN public.tractors tra ON tra.id = a.tractor_id "
            + "LEFT JOIN public.trailers tri ON tri.id = a.trailer_id "
            + "WHERE a.service_id = :serviceId ";

    /**
     * Los refuerzos de un viaje, en el orden en que se sumaron.
     *
     * <p>Se desempata por {@code id} y no solo por la fecha: dos refuerzos cargados dentro del mismo
     * microsegundo dejarian el orden a criterio del motor, y una bitacora que se reordena sola entre
     * dos lecturas no es una bitacora.
     */
    public List<ServiceAdditionalResourceRow> listByServiceId(long serviceId) {
        Query query = entityManager.createNativeQuery(
            ADDITIONAL_RESOURCE_SELECT + "ORDER BY a.assigned_at, a.id", Tuple.class);
        query.setParameter("serviceId", serviceId);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        List<ServiceAdditionalResourceRow> resources = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            resources.add(toAdditionalResourceRow(row));
        }
        return resources;
    }

    /**
     * UN refuerzo por su id, ACOTADO a su viaje.
     *
     * <p>El id del viaje entra en la CONSULTA y no se compara despues. Asi un refuerzo de otro
     * viaje no se puede tocar, y ademas no se distingue de uno inexistente: los dos devuelven vacio
     * y el endpoint contesta lo mismo, sin abrir un canal para averiguar que ids estan vivos.
     */
    public Optional<ServiceAdditionalResourceRow> findByIdAndServiceId(long assignmentId, long serviceId) {
        Query query = entityManager.createNativeQuery(
            ADDITIONAL_RESOURCE_SELECT + "AND a.id = :assignmentId", Tuple.class);
        query.setParameter("serviceId", serviceId);
        query.setParameter("assignmentId", assignmentId);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(toAdditionalResourceRow(rows.get(0)));
    }

    /**
     * Baja FISICA de un refuerzo, acotada a su viaje.
     *
     * <p>⚠️ El que no tiene red propia es ESTE filtro: romperlo solo no se nota, porque la busqueda
     * de arriba ya rechazo el id ajeno. Al reves NO es simetrico —el filtro de
     * {@link #findByIdAndServiceId} vive en {@link #ADDITIONAL_RESOURCE_SELECT}, que comparte
     * {@link #listByServiceId}, asi que romperlo pone en rojo al detalle y a los refuerzos—. Dentro
     * de la clase de test de la BAJA hacen falta los dos para que algo se caiga; fuera de ella, no.
     * Se conserva para que un llamador futuro que se saltee la busqueda no borre lo ajeno.
     *
     * @return cuantas filas se borraron: 1 si estaba, 0 si no. El llamador ya la busco, asi que un
     *     0 aca significa que otra transaccion se le adelanto.
     */
    public long deleteByIdAndServiceId(long assignmentId, long serviceId) {
        // Misma guarda que el chequeo de duplicados, y por el mismo motivo: esto se apoya en que la
        // fila del viaje YA este lockeada. Sin ese lock, dos bajas simultaneas del mismo refuerzo no
        // se ven entre si y las dos escriben rastro.
        requireServiceRowAlreadyLocked();
        return delete("id = ?1 and serviceId = ?2", assignmentId, serviceId);
    }

    private static ServiceAdditionalResourceRow toAdditionalResourceRow(Tuple row) {
        return new ServiceAdditionalResourceRow(
            ((Number) row.get(0)).longValue(),
            ServiceRepository.toInteger(row.get(1)), (String) row.get(2),
            ServiceRepository.toInteger(row.get(3)), (String) row.get(4),
            ServiceRepository.toInteger(row.get(5)), (String) row.get(6),
            (String) row.get(7),
            ServiceRepository.toInteger(row.get(8)),
            // NO se castea directo: segun la version del driver la misma columna llega como
            // Instant, Timestamp u OffsetDateTime. Mismo helper que el listado de viajes.
            DateUtils.toOffsetDateTime(row.get(9)));
    }

    /**
     * Cuales de los recursos pedidos YA participan de ESTE MISMO viaje, mirando las dos fuentes: las
     * columnas de recurso del viaje (los principales) y sus filas de refuerzo.
     *
     * <p>Es el insumo del rechazo DURO. No se puede sacar de la consulta de conflictos, que hace lo
     * contrario por diseño: aquella EXCLUYE el viaje propio en sus dos ramas, justamente para no
     * confundir "el recurso esta ocupado en otro lado" con "ya esta en este viaje", que son un
     * conflicto forzable y un pedido sin sentido.
     *
     * <p>NO toma locks, y no le hacen falta: quien llama ya tiene la fila del viaje tomada, y eso
     * serializa a dos refuerzos concurrentes sobre el mismo viaje. Sumar una espera aca gastaria
     * presupuesto de lock sin comprar nada.
     *
     * <p>Solo se pregunta por los recursos PRESENTES: cada uno agrega dos ramas a la consulta, y
     * armarlas para un id que no vino ademas obligaria a tipar el null en SQL.
     */
    public Set<ServiceResourceKind> findResourcesAlreadyInService(
            long serviceId, Integer driverId, Integer tractorId, Integer trailerId) {
        requireServiceRowAlreadyLocked();

        List<String> branches = new ArrayList<>();
        List<Object[]> parameters = new ArrayList<>();
        addBranches(branches, parameters, ServiceResourceKind.DRIVER, DRIVER_COLUMN, driverId);
        addBranches(branches, parameters, ServiceResourceKind.TRACTOR, TRACTOR_COLUMN, tractorId);
        addBranches(branches, parameters, ServiceResourceKind.TRAILER, TRAILER_COLUMN, trailerId);
        if (branches.isEmpty()) {
            return EnumSet.noneOf(ServiceResourceKind.class);
        }

        Query query = entityManager.createNativeQuery(String.join(" UNION ", branches));
        query.setParameter("serviceId", serviceId);
        for (Object[] parameter : parameters) {
            query.setParameter((String) parameter[0], parameter[1]);
        }

        @SuppressWarnings("unchecked")
        List<Object> kinds = query.getResultList();
        Set<ServiceResourceKind> alreadyInService = EnumSet.noneOf(ServiceResourceKind.class);
        for (Object kind : kinds) {
            alreadyInService.add(ServiceResourceKind.valueOf((String) kind));
        }
        return alreadyInService;
    }

    /**
     * Corta si quien llama todavia no tomo la fila del viaje.
     *
     * <p>La usan los DOS caminos que tocan refuerzos apoyandose en ese lock, y sin ella el olvido
     * no falla en ninguno: el chequeo de duplicados devuelve "sin duplicado" y dos refuerzos
     * concurrentes entran los dos (no hay indice unico que los frene), y la baja borra sin
     * serializar, con lo cual dos bajas simultaneas escriben rastro las dos. Sigue el mismo patron
     * que {@code ServiceResourceConflictRepository.requirePreconditions()}.
     *
     * <p>Se detecta por el tope de espera, que lo pone la lectura con lock de la fila y solo ella.
     * No prueba que sea la fila CORRECTA, pero cierra el olvido real: llamar antes de lockear.
     *
     * <p>⚠️ Depende de que el tope NO venga puesto desde afuera. Un {@code lock_timeout} global en
     * el servidor (un parametro de la instancia, o un SQL de inicializacion del pool) satisface las
     * DOS guardas del modulo sin que nadie haya lockeado nada, y vuelve el olvido silencioso otra
     * vez. Hoy el default es 0 en dev y en la nube; si eso cambia, hay que comparar contra el valor
     * configurado en vez de contra cero.
     */
    private void requireServiceRowAlreadyLocked() {
        Object[] settings = (Object[]) entityManager.createNativeQuery(
                "SELECT current_setting('lock_timeout'), current_setting('transaction_isolation')")
            .getSingleResult();
        if ("0".equals(settings[0])) {
            throw new IllegalStateException(
                "esta consulta se apoya en el lock de la fila del viaje, que todavia no se tomo: "
                    + "sin el, dos escrituras simultaneas sobre los refuerzos del mismo viaje no se "
                    + "ven entre si.");
        }
        // Se verifica por SIMETRIA con la hermana y para que este flow declare el nivel del que
        // depende. Con la guarda puesta, las dos cortan acá con 500 y ese es el punto; lo que
        // cambia entre las dos es la CONSECUENCIA DE BORRARLA, y por eso se escribe:
        //
        //  - Borrada la de la consulta de conflictos: dos viajes DISTINTOS contienden solo por un
        //    advisory, y los advisories no disparan 40001. Bajo REPEATABLE READ cada uno lee su
        //    propia foto, ninguno ve al otro, los dos contestan 200 y quedan compartiendo el
        //    recurso. Falla SILENCIOSA.
        //  - Borrada esta, Y con el aislamiento subido (el unico mundo donde esta mitad importa):
        //    dos refuerzos del MISMO viaje contienden por la fila de operaciones.services, que el
        //    ganador actualiza al mover la version, asi que el perdedor despierta sobre una tupla
        //    ya confirmada, aborta con 40001 y sale por el 409 transitorio. Falla RUIDOSA.
        //    OJO: hoy, con READ COMMITTED, ese 40001 NO ocurre — la relectura ve la version
        //    confirmada y sigue de largo, como explica el javadoc de ServiceRowLock.
        //
        // O sea que acá la mitad del aislamiento compra menos que allá, y aun asi se pone: la
        // diferencia depende de que esta consulta corra DESPUES del lock de la fila, y el dia que
        // alguien la mueva —o aparezca un llamador que consulte duplicados antes de lockear— lo
        // unico que queda tapando el agujero es la otra mitad, la del tope de espera.
        if (!"read committed".equals(settings[1])) {
            throw new IllegalStateException(
                "las consultas de refuerzos que se apoyan en el lock de la fila del viaje necesitan "
                    + "READ COMMITTED para ver lo que confirmo la transaccion que lo gano; nivel "
                    + "actual: " + settings[1]);
        }
    }

    /**
     * Las DOS ramas de un recurso presente: como principal del viaje y como refuerzo previo. El
     * nombre del parametro sale del tipo, no de un contador, para que la consulta se lea sola
     * cuando aparece en un log.
     */
    private static void addBranches(List<String> branches, List<Object[]> parameters,
            ServiceResourceKind kind, String column, Integer resourceId) {
        if (resourceId == null) {
            return;
        }
        // Locale.ROOT explicito, como la clave del advisory (el otro uso del modulo es un
        // mensaje al usuario, no una clave): con locale turco
        // "DRIVER" se minusculiza a "dr\u0131ver" y el nombre del parametro deja de ser el que
        // se lee en el log de la consulta.
        String parameterName = kind.name().toLowerCase(Locale.ROOT) + "Id";
        branches.add("SELECT '" + kind.name() + "' AS kind FROM operaciones.services s "
            + "WHERE s.id = :serviceId AND s." + column + " = :" + parameterName);
        branches.add("SELECT '" + kind.name() + "' AS kind FROM operaciones.service_assignments a "
            + "WHERE a.service_id = :serviceId AND a." + column + " = :" + parameterName);
        parameters.add(new Object[] { parameterName, resourceId });
    }

    /**
     * Un refuerzo con sus etiquetas resueltas. Los tres ids pueden ser null (la fila trae solo los
     * recursos que se pidieron); cuando un id viene, su etiqueta viene con el.
     */
    public record ServiceAdditionalResourceRow(
        Long id,
        Integer driverId,
        String driverFullName,
        Integer tractorId,
        String tractorPlate,
        Integer trailerId,
        String trailerPlate,
        String reason,
        Integer assignedBy,
        OffsetDateTime assignedAt
    ) {}
}
