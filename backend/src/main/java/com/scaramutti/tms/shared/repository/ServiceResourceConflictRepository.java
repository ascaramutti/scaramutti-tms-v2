package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.operations.model.ServiceResourceKind;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.service.ServiceResourceLockKeys;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Quien mas esta usando los recursos que un viaje quiere tomar.
 *
 * <p>Es una clase propia y no dos metodos en {@link ServiceRepository} porque las dos cosas que
 * hace son una sola: el lock existe para que la respuesta de la consulta siga siendo cierta
 * hasta el commit. Separarlas invita a usar la consulta sin el lock, que es exactamente el
 * defecto que cierran juntas.
 */
@ApplicationScoped
public class ServiceResourceConflictRepository {

    /**
     * Los estados en los que un viaje RETIENE sus recursos. Antes de asignar no tiene ninguno, y
     * los tres terminales (completado, cancelado, eliminado) ya los liberaron: un conductor no
     * queda ocupado para siempre por un viaje que termino.
     *
     * <p>El contrato declara este mismo par como dominio de {@code conflicts[].serviceStatus}, asi
     * que los dos tienen que moverse juntos.
     */
    private static final Set<ServiceStatus> RESOURCE_HOLDING_STATUSES =
        EnumSet.of(ServiceStatus.PENDING_START, ServiceStatus.IN_PROGRESS);

    @Inject EntityManager entityManager;

    /**
     * Serializa entre si a las asignaciones que comparten algun recurso, hasta el fin de la
     * transaccion.
     *
     * <p>Hace falta porque el lock de la FILA del viaje no alcanza: protege al viaje que se
     * asigna, no a los otros, asi que dos asignaciones simultaneas del mismo conductor a dos
     * viajes distintos consultarian las dos antes de que la otra escriba, ninguna veria conflicto
     * y las dos terminarian en 200. El estado resultante lo acepta el negocio (el conflicto es
     * forzable a proposito), pero llegaria SIN la linea de bitacora que dice que se forzo, y un
     * dato sin rastro es justo lo que este modulo existe para impedir.
     *
     * <p>El orden en el que se toman lo fija {@link ServiceResourceLockKeys}, que es donde esta
     * explicado por que ese orden evita el abrazo mortal.
     *
     * <p>Se usa la forma de UNA clave y no la de dos a proposito: PostgreSQL mantiene espacios
     * separados para cada forma, y la de dos ya la usan las guardas anti doble-click del alta de
     * viajes y de cotizaciones — con esa forma, un par (usuario, cliente) podria coincidir
     * numericamente con un par (tipo, recurso) y hacerlos esperar entre si sin ninguna razon.
     * El espacio de una clave NO esta vacio: ya lo usan el lock por anio del alta de cotizaciones
     * y el del codigo de producto de almacen. Colisionar con ellos exige que {@code hashtext} de
     * esta cadena caiga exactamente en el anio en curso o en ese hash, y el precio de esa
     * casualidad seria una espera de milisegundos, no un dato mal escrito.
     *
     * <p>El {@code lock_timeout} de la transaccion tambien rige aca, asi que una espera agotada
     * sale por el mismo 409 transitorio que el resto de la operacion.
     */
    public void acquireResourceLocks(Integer driverId, Integer tractorId, Integer trailerId) {
        // Una sola vez para las tres claves: el tope y el nivel de aislamiento no pueden cambiar
        // dentro de una transaccion, asi que revalidarlos por recurso son dos viajes de ida y
        // vuelta a la base por cada uno, sin comprar nada.
        requirePreconditions();
        for (String key : ServiceResourceLockKeys.ordered(driverId, tractorId, trailerId)) {
            takeLock(key);
        }
    }

    /** Toma el lock sin revalidar: solo para uso interno, con las precondiciones ya verificadas. */
    private void takeLock(String lockKey) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:key)::bigint)")
            .setParameter("key", lockKey)
            .getSingleResult();
    }

    /**
     * Corta si el tope de espera todavia no esta puesto. Es la mitad verificable de lo que dice
     * el javadoc de arriba: el tope lo pone la lectura con lock de la fila del viaje, y sin ella
     * PostgreSQL espera PARA SIEMPRE por el advisory. Cada request en cola inmovilizaria un hilo
     * y una conexion del pool, que se comparte con los otros modulos.
     *
     * <p>Sin esta guarda, el olvido se paga con la aplicacion clavada en produccion en vez de con
     * un error ruidoso, y el proximo endpoint que necesite estos locks (el de recursos de
     * refuerzo) es justamente uno que podria llamar aca antes de tomar la fila.
     */
    private void requirePreconditions() {
        Object[] settings = (Object[]) entityManager.createNativeQuery(
                "SELECT current_setting('lock_timeout'), current_setting('transaction_isolation')")
            .getSingleResult();
        if ("0".equals(settings[0])) {
            throw new IllegalStateException(
                "el lock de recursos exige el tope de espera ya puesto: hay que tomar antes el "
                    + "lock de la fila del viaje, que es quien lo aplica. Sin tope, la espera por "
                    + "el lock de un recurso no termina nunca.");
        }
        // La correccion de la consulta depende del nivel de aislamiento, y la falla seria
        // SILENCIOSA: con REPEATABLE READ el que espera por el lock consultaria sobre la foto del
        // inicio de su transaccion, no veria al ganador ya confirmado, y las dos asignaciones
        // terminarian en 200 sin que ninguna deje la linea que dice que se piso un conflicto.
        // PostgreSQL no avisaria: no hay choque de escritura sobre una fila comun.
        if (!"read committed".equals(settings[1])) {
            throw new IllegalStateException(
                "la consulta de conflictos necesita READ COMMITTED para ver lo que confirmo la "
                    + "transaccion que gano el lock; nivel actual: " + settings[1]);
        }
    }

    /**
     * Toma UN lock por su clave ya derivada. Visible para que el test pueda retener uno.
     *
     * <p>Verifica las precondiciones IGUAL que el metodo de arriba, y no por simetria: este es el
     * que de verdad ESPERA. Dejarlo sin guarda lo convierte en la trampa obvia para el proximo
     * endpoint que necesite lockear un solo recurso — lo llamaria sin tomar antes la fila del
     * viaje, y sin tope de espera esa espera no termina nunca.
     */
    public void acquireLock(String lockKey) {
        requirePreconditions();
        takeLock(lockKey);
    }

    /**
     * Intenta tomar el lock sin esperar. No lo usa el endpoint: existe para que un test pueda
     * preguntar si una clave esta tomada sin quedarse colgado, que es lo unico que vuelve
     * DETERMINISTA la verificacion de que el lock existe de verdad.
     */
    public boolean tryAcquireLock(String lockKey) {
        return (Boolean) entityManager
            .createNativeQuery("SELECT pg_try_advisory_xact_lock(hashtext(:key)::bigint)")
            .setParameter("key", lockKey)
            .getSingleResult();
    }

    /**
     * Los recursos pedidos que ya estan tomados por OTRO viaje activo, mirando las DOS fuentes:
     * el recurso PRINCIPAL de ese otro viaje (columnas de {@code services}) y sus REFUERZOS
     * (filas de {@code service_assignments}).
     *
     * <p>Revisar las dos es el punto entero de este metodo: el sistema anterior, al asignar,
     * solo miraba los principales, con lo cual un conductor sumado como refuerzo a un viaje en
     * ruta figuraba como libre y se lo podia asignar a otro sin ningun aviso.
     *
     * <p>Devuelve el codigo y el estado del viaje que RETIENE el recurso, no los del que se esta
     * asignando: es lo que necesita ver quien decide si fuerza. El nombre del recurso no sale de
     * aca — los conflictos solo pueden ser sobre los tres que se pidieron, y esos ya los resolvio
     * quien llama para poder validarlos.
     *
     * <p><b>{@code excludedServiceId} hoy no es alcanzable, y se deja igual.</b> Medido con una
     * mutacion: quitando esa condicion, ningun test se pone rojo. La razon es que el filtro de
     * estados ya la contiene — desde la asignacion solo se llama con el viaje en "pendiente de
     * asignacion", que no es un estado que retenga recursos, asi que la fila propia nunca entra
     * en el resultado. Se conserva porque el endpoint de refuerzos va a llamar a esta misma
     * consulta con el viaje YA en ruta, y ahi si se encontraria a si mismo: el choque contra
     * OTRO viaje es el conflicto forzable, y el choque contra el PROPIO viaje es un error duro
     * distinto. Escrito aca para que no se lo lea como una condicion de mas y se lo borre.
     *
     * <p><b>Toma los locks de los recursos consultados antes de consultar.</b> No es un efecto
     * colateral escondido: es la unica forma de que la respuesta siga siendo cierta hasta el
     * commit, y dejarlo en manos de quien llama convierte un olvido en dos transacciones que
     * contestan 200 sin que ninguna deje la linea que dice que se piso un conflicto.
     *
     * <p>Sin recursos que consultar no se arma consulta: un {@code UNION} vacio es SQL invalido.
     */
    public List<ServiceResourceConflictRow> findActiveConflicts(
            long excludedServiceId, Integer driverId, Integer tractorId, Integer trailerId) {
        // El lock lo toma ESTE metodo, no quien llama. Atarlo asi es lo unico que vuelve
        // verificable lo que el javadoc de la clase promete: separados, olvidarse del lock no
        // falla, devuelve 200 en las dos transacciones y ninguna deja rastro de que se piso un
        // conflicto. Volver a pedir un lock que la propia transaccion ya tiene solo incrementa un
        // contador, asi que a quien ya lo tomo no le cuesta nada.
        acquireResourceLocks(driverId, tractorId, trailerId);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("excludedServiceId", excludedServiceId);
        String holdingStatuses = holdingStatusesIn(params);

        List<String> branches = new ArrayList<>();
        addConflictBranches(branches, params, ServiceResourceKind.DRIVER, "driver_id", driverId, holdingStatuses);
        addConflictBranches(branches, params, ServiceResourceKind.TRACTOR, "tractor_id", tractorId, holdingStatuses);
        addConflictBranches(branches, params, ServiceResourceKind.TRAILER, "trailer_id", trailerId, holdingStatuses);
        if (branches.isEmpty()) {
            return List.of();
        }

        // DISTINCT: el mismo recurso puede estar como principal Y como refuerzo del mismo viaje,
        // y eso es UN conflicto, no dos. ORDER BY: sin el, el orden de un UNION no esta definido,
        // y de ese orden dependen tanto el mensaje del error (nombra al primero) como los tests.
        String sql = "SELECT DISTINCT resource, service_code, service_status FROM ("
            + String.join(" UNION ALL ", branches)
            + ") conflicts ORDER BY resource, service_code";

        Query query = entityManager.createNativeQuery(sql, Tuple.class);
        params.forEach(query::setParameter);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        return rows.stream()
            .map(row -> new ServiceResourceConflictRow(
                ServiceResourceKind.valueOf((String) row.get(0)),
                (String) row.get(1),
                ServiceStatus.valueOf((String) row.get(2))))
            .toList();
    }

    /** Las dos ramas de un recurso: como principal de otro viaje, y como refuerzo de otro viaje. */
    private void addConflictBranches(List<String> branches, Map<String, Object> params,
            ServiceResourceKind kind, String column, Integer resourceId, String holdingStatuses) {
        if (resourceId == null) {
            return;
        }
        String param = "resource" + kind.name();
        params.put(param, resourceId);

        branches.add(
            "SELECT '" + kind.name() + "' AS resource, other.code AS service_code, "
                + "other.status AS service_status "
                + "FROM operaciones.services other "
                + "WHERE other." + column + " = :" + param
                + " AND other.id <> :excludedServiceId"
                + " AND other.status IN (" + holdingStatuses + ")");

        branches.add(
            "SELECT '" + kind.name() + "', other.code, other.status "
                + "FROM operaciones.service_assignments extra "
                + "JOIN operaciones.services other ON other.id = extra.service_id "
                + "WHERE extra." + column + " = :" + param
                + " AND extra.service_id <> :excludedServiceId"
                + " AND other.status IN (" + holdingStatuses + ")");
    }

    /**
     * Los estados que retienen recursos, como lista de parametros con nombre. Se derivan del
     * conjunto de arriba en vez de escribirse literales en el SQL: asi agregar un estado que
     * retenga recursos es un cambio en un solo lugar.
     */
    private String holdingStatusesIn(Map<String, Object> params) {
        List<String> placeholders = new ArrayList<>();
        int index = 0;
        for (ServiceStatus status : RESOURCE_HOLDING_STATUSES) {
            String name = "holdingStatus" + index++;
            params.put(name, status.name());
            placeholders.add(":" + name);
        }
        return String.join(", ", placeholders);
    }

    /**
     * Un recurso pedido que otro viaje retiene. {@code serviceCode} y {@code serviceStatus} son
     * los del viaje que lo retiene.
     */
    public record ServiceResourceConflictRow(
        ServiceResourceKind resource,
        String serviceCode,
        ServiceStatus serviceStatus
    ) {}
}
