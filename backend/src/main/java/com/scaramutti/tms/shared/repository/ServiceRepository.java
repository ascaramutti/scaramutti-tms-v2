package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Service;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Repositorio del servicio de transporte. El id es {@code Long} porque la tabla usa
 * {@code BIGSERIAL} (el resto de los modulos usa SERIAL/Integer).
 */
@ApplicationScoped
public class ServiceRepository implements PanacheRepositoryBase<Service, Long> {

    @Inject
    EntityManager entityManager;

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
