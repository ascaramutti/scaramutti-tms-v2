package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.DocumentType;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class DocumentTypeRepository implements PanacheRepositoryBase<DocumentType, Integer> {

    /**
     * Los tipos vigentes, por id ascendente: es el orden en que se cargaron y el unico
     * estable. Por nombre pondria el carnet de extranjeria antes que el documento nacional,
     * que es el que usa casi todo el padron.
     *
     * <p>Solo activos: un tipo retirado no se puede elegir en un alta. El detalle de un
     * trabajador SI muestra el suyo aunque este retirado, y por eso no usa este metodo.
     */
    public List<DocumentType> listActiveOrderedById() {
        return list("isActive", Sort.by("id").ascending(), true);
    }
}
