package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Trailer;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Repositorio de solo-lectura de Trailer (catálogo de flota en {@code public}). Almacén lo lee
 * para validar y embeber la unidad destino de un retiro; no escribe.
 */
@ApplicationScoped
public class TrailerRepository implements PanacheRepositoryBase<Trailer, Integer> {
}
