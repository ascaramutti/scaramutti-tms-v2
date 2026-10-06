package com.scaramutti.tms.workers.service;

import com.scaramutti.tms.shared.repository.RoleRepository;
import com.scaramutti.tms.workers.dto.RoleResponse;
import com.scaramutti.tms.workers.mapper.RoleServiceMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Catalogo de cargos: las filas vigentes de la tabla de roles, en el orden del organigrama.
 * Lectura, sin transaccion propia, como los otros listados de catalogo.
 */
@ApplicationScoped
public class RoleService {

    @Inject RoleRepository roleRepository;
    @Inject RoleServiceMapper roleServiceMapper;

    public List<RoleResponse> listRoles() {
        return roleServiceMapper.toRoleResponseList(roleRepository.listActiveByLevelDesc());
    }
}
