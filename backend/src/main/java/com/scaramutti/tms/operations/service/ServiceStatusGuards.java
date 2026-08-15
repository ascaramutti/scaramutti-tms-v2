package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.OperationsError;
import com.scaramutti.tms.operations.model.ServiceStatus;

import java.util.Set;

/**
 * Los dos estados que congelan la fila, y el rechazo que les corresponde.
 *
 * <p>El completado queda AFUERA a proposito y esa es la parte que se copia mal: corregir los datos
 * de un viaje ya cerrado es legitimo, lo que no tiene es a donde ir. Terminal para la maquina de
 * estados no es lo mismo que inmutable para la fila, y son dos preguntas que se confunden.
 *
 * <p>Existe porque es el TERCER endpoint que necesita exactamente esta guarda (la edicion, la
 * asignacion y las transiciones). Con una copia por servicio, al proximo que la escriba de memoria
 * se le puede colar el completado adentro, y una escritura aterriza sobre un viaje que ya nadie
 * puede corregir.
 */
public final class ServiceStatusGuards {

    /** Cancelado y eliminado: las dos salidas del ciclo. Congelan la fila para toda escritura, con una
     * unica excepcion: la reapertura, que existe para deshacerlas y por eso no pasa por aca. */
    public static final Set<ServiceStatus> IMMUTABLE_STATUSES =
        Set.of(ServiceStatus.CANCELLED, ServiceStatus.DELETED);

    /**
     * A donde NO se puede volver: los tres finales del ciclo. Es la otra mitad de la pregunta que
     * este archivo separa, y por eso son dos conjuntos y no uno. La reapertura restaura el estado
     * que el rastro registre, y ese rastro puede traer cualquier cosa (data migrada, o SQL a mano);
     * aterrizar en un terminal dejaria el viaje fuera de listados e indicadores con una bitacora
     * que dice "vuelve a eliminado".
     *
     * <p>Se declara ACA y no se deriva de "los estados sin arcos de salida" de la maquina, que hoy
     * es el mismo conjunto. La maquina describe el DOMINIO —carga a proposito arcos que ningun
     * endpoint pide— asi que agregarle uno es un cambio de bajo ceremonial: el dia que alguien sume
     * "de completado a en ruta", una guarda derivada empezaria a aceptar el completado como destino
     * de reapertura sin que nadie lo decida. Un test afirma que hoy los dos conjuntos coinciden,
     * para que el dia que dejen de hacerlo lo diga el test y no el comportamiento.
     */
    public static final Set<ServiceStatus> NON_RESTORABLE_STATUSES = Set.of(
        ServiceStatus.COMPLETED, ServiceStatus.CANCELLED, ServiceStatus.DELETED);

    private ServiceStatusGuards() {}

    /** 409 OPS-004 si la fila ya no admite ningun cambio. */
    public static void rejectIfImmutable(ServiceStatus status) {
        if (IMMUTABLE_STATUSES.contains(status)) {
            throw OperationsError.SERVICE_NOT_EDITABLE.toException();
        }
    }
}
