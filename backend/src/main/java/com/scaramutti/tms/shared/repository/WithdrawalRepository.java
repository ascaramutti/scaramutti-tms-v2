package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Withdrawal;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Repositorio de retiros. Vive en {@code shared/repository/} por convención del proyecto. El
 * alta usa el {@code persist} de Panache; el listado paginado (searchPaged/countSearch) lo
 * agrega el endpoint de listado.
 */
@ApplicationScoped
public class WithdrawalRepository implements PanacheRepositoryBase<Withdrawal, Integer> {
}
