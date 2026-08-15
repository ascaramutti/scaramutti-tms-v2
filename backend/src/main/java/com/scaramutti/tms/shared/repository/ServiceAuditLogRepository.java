package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.ServiceAuditLog;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/** Repositorio de la auditoria del servicio. */
@ApplicationScoped
public class ServiceAuditLogRepository implements PanacheRepositoryBase<ServiceAuditLog, Long> {

    /**
     * El estado que el viaje tenia ANTES de su ultimo cambio de estado, leido del rastro.
     *
     * <p>Lo usa la reapertura para saber a donde volver: un viaje cancelado en ruta tiene que
     * volver a en ruta, no a foja cero, y el unico que sabe eso es el registro que se escribio al
     * cancelarlo. Devuelve {@code null} cuando no hay tal fila —los viajes que traiga el cutover
     * del sistema anterior no la van a tener—, y ahi la reapertura se rechaza en vez de inventar
     * un destino.
     *
     * <p>No se busca "la ultima fila" sino <b>la fila que PUSO al viaje en el estado en el que
     * esta</b>, filtrando por su {@code newValue}. La diferencia importa: buscar la mas reciente
     * obliga a confiar en un orden, y los dos ordenes posibles fallan por lados distintos. Por id,
     * las filas que importe el cutover se leen en el orden en que las inserto el script, que no
     * tiene por que ser el cronologico. Por fecha, se depende del reloj de la instancia que
     * escribio, y dos replicas desfasadas invierten dos filas. Las dos fallas devuelven un estado
     * ANTERIOR al que el viaje tenia —uno legal, asi que ninguna guarda lo nota—.
     *
     * <p>Filtrando por {@code newValue}, ninguna fila que hable de OTRA transicion puede ganar,
     * venga del cutover o de donde venga. Lo que queda por desempatar son ciclos repetidos del
     * MISMO arco (cancelar, reabrir, cancelar otra vez), y esas filas las escribio siempre la
     * aplicacion, una por transaccion y serializadas por el lock de la fila: su orden de id ES su
     * orden de commit. Por eso se ordena por id y no por la marca, que la pone el reloj de la
     * instancia que escribio y desordenaria dos replicas desfasadas.
     */
    public String findPreviousStatusOf(long serviceId, String currentStatus) {
        return find("serviceId = ?1 and fieldName = ?2 and newValue = ?3 order by id desc",
            serviceId, "status", currentStatus)
            .firstResultOptional()
            .map(auditLog -> auditLog.oldValue)
            .orElse(null);
    }
}
