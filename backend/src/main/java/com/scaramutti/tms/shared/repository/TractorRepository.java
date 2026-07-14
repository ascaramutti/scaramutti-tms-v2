package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Tractor;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Repositorio de solo-lectura de Tractor (catálogo de flota en {@code public}). Almacén lo lee
 * para validar y embeber la unidad destino de un retiro; no escribe.
 */
@ApplicationScoped
public class TractorRepository implements PanacheRepositoryBase<Tractor, Integer> {
}
