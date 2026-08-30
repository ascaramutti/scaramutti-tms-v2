package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.operations.model.ServiceStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import java.time.OffsetDateTime;

/**
 * Repositorio del strip de indicadores del tablero operativo (GET /services/stats). Vive en
 * {@code shared/repository/} por la misma convencion que {@link WarehouseStatsRepository}: cruza
 * {@code operaciones.services} con las dos tablas de flota de {@code public} y no tiene una entity
 * 1:1 detras.
 *
 * <p><b>Todo sale en UNA sola consulta</b>, y el motivo no es el costo: con ~2.000 viajes al año y
 * decenas de filas de flota, cualquier forma corre en milisegundos. Lo que decide es que la
 * POLITICA DE EXCLUSION se escriba una vez y no seis. Con una consulta por contador, "cancelados y
 * eliminados afuera" y "solo el recurso principal" quedan repetidos en seis lugares, y basta que
 * alguien toque cinco para que el tablero mienta sin que nada falle. Es el mismo argumento con el
 * que el listado comparte su FROM y su WHERE con su total.
 *
 * <p>Efecto lateral bueno de los agregados condicionales: como cada uno NOMBRA su estado, los
 * cancelados y los eliminados no pueden entrar en ningun contador por construccion — no hay una
 * clausula de exclusion que se pueda borrar.
 */
@ApplicationScoped
public class ServiceStatsRepository {

    @Inject
    EntityManager entityManager;

    /**
     * Los ocho numeros del strip.
     *
     * <p><b>Sobre los recursos: lo que NO esta en esta consulta es una decision, no un olvido.</b>
     * Se cuenta la columna del viaje ({@code s.driver_id}, {@code s.tractor_id}) y NO se une contra
     * {@code operaciones.service_assignments}, que es donde viven los refuerzos. La regla del
     * negocio (D-OPS-16) es que el indicador mida lo mismo que el tablero del sistema anterior,
     * para que el numero no cambie de significado el dia del cambio de sistema; contar los
     * refuerzos seria una mejora consciente posterior, no un arreglo.
     *
     * <p>Se aclara porque el modulo tiene el habito CONTRARIO: la consulta de conflictos mira las
     * dos fuentes a proposito (D-OPS-17), asi que quien lea esto sin contexto va a pensar que falta
     * un JOIN. Lo mismo vale para {@code s.trailer_id}, que esta justo al lado y tampoco se cuenta:
     * el indicador es de TRACTOS, pese a que el contrato llame al campo "units".
     *
     * <p>{@code COUNT(DISTINCT ...)} y no {@code COUNT(*)}: un mismo conductor en dos viajes en
     * ruta es UNA persona en la calle. Y esa fila la produce la aplicacion A PROPOSITO: el
     * conflicto de recursos es FORZABLE (RN-OP3), asi que el despacho puede poner al mismo
     * conductor en un segundo viaje y despues arrancar los dos; el cutover ademas puede traerla.
     * El {@code DISTINCT} ignora los nulos, que es justo lo que hace falta para el viaje sin
     * conductor que tambien puede llegar del sistema anterior.
     *
     * <p>Los dos numeradores exigen ademas que el recurso este de ALTA, igual que los
     * denominadores. Es una DESVIACION consciente del sistema anterior, que solo lo pedia abajo y
     * por eso podia mostrar "6 de 5".
     *
     * <p>Lo que NINGUNO de los cuatro numeros mira es la DISPONIBILIDAD del catalogo
     * ({@code status_id}), y se escribe porque el error contrario es razonable: un conductor en
     * ruta figura NO DISPONIBLE, que es justamente como tiene que estar, asi que exigir
     * "disponible" arriba dejaria el numerador clavado en cero.
     */
    public ServiceStatsRow getStats(OffsetDateTime weekStart, OffsetDateTime weekEndExclusive) {
        Tuple row = (Tuple) entityManager.createNativeQuery(
            "SELECT "
            + "COUNT(*) FILTER (WHERE s.status = :pendingAssignment) AS pending_assignment, "
            + "COUNT(*) FILTER (WHERE s.status = :pendingStart) AS pending_start, "
            + "COUNT(*) FILTER (WHERE s.status = :inProgress) AS in_progress, "
            + "COUNT(*) FILTER (WHERE s.status = :completed "
            + "                   AND s.end_date_time >= :weekStart "
            + "                   AND s.end_date_time < :weekEndExclusive) AS completed_this_week, "
            // Los principales del viaje. NO se une service_assignments: ver el javadoc.
            + "COUNT(DISTINCT s.driver_id) FILTER (WHERE s.status = :inProgress AND d.is_active) "
            + "  AS principal_drivers_on_road, "
            + "COUNT(DISTINCT s.tractor_id) FILTER (WHERE s.status = :inProgress AND t.is_active) "
            + "  AS principal_tractors_on_road, "
            + "(SELECT COUNT(*) FROM public.drivers WHERE is_active) AS active_drivers_total, "
            + "(SELECT COUNT(*) FROM public.tractors WHERE is_active) AS active_tractors_total "
            + "FROM operaciones.services s "
            // Uniones EXTERNAS: un viaje sin recursos, o con uno dado de baja, no puede
            // desaparecer de los contadores de VIAJES, que no dependen de la flota.
            + "LEFT JOIN public.drivers d ON d.id = s.driver_id "
            + "LEFT JOIN public.tractors t ON t.id = s.tractor_id",
            Tuple.class)
            .setParameter("pendingAssignment", ServiceStatus.PENDING_ASSIGNMENT.name())
            .setParameter("pendingStart", ServiceStatus.PENDING_START.name())
            .setParameter("inProgress", ServiceStatus.IN_PROGRESS.name())
            .setParameter("completed", ServiceStatus.COMPLETED.name())
            .setParameter("weekStart", weekStart)
            .setParameter("weekEndExclusive", weekEndExclusive)
            .getSingleResult();

        // Por ALIAS y no por posicion. Leyendo por indice, el orden del SELECT y el del record no
        // coinciden (el record intercala numerador y total de cada par, la consulta agrupa los
        // numeradores primero), asi que habia que cruzar dos indices a mano; y una columna nueva
        // insertada en el medio del SELECT corre todo lo de abajo y cambia el total de conductores
        // por los tractos en ruta, en silencio y sin error de compilacion, porque los ocho son
        // enteros. Es el mismo cruce contra el que advierte el service al armar la respuesta.
        return new ServiceStatsRow(
            count(row, "pending_assignment"),
            count(row, "pending_start"),
            count(row, "in_progress"),
            count(row, "completed_this_week"),
            count(row, "principal_drivers_on_road"),
            count(row, "active_drivers_total"),
            count(row, "principal_tractors_on_road"),
            count(row, "active_tractors_total")
        );
    }

    /** Postgres devuelve los {@code COUNT} como {@code Long}; el contrato los publica como enteros. */
    private static int count(Tuple row, String alias) {
        return ((Number) row.get(alias)).intValue();
    }

    /**
     * Los ocho numeros, en PLANO. Los nombres dicen "principal" a proposito: la decision de no
     * contar los refuerzos viaja hasta cada lugar donde se use, en vez de quedarse en la consulta.
     * Solo el campo del contrato se llama {@code driversOnRoad} / {@code unitsOnRoad}.
     */
    public record ServiceStatsRow(
        int pendingAssignment,
        int pendingStart,
        int inProgress,
        int completedThisWeek,
        int principalDriversOnRoad,
        int activeDriversTotal,
        int principalTractorsOnRoad,
        int activeTractorsTotal
    ) {}
}
