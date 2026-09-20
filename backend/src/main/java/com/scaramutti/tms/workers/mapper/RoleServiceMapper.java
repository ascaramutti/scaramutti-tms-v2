package com.scaramutti.tms.workers.mapper;

import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.workers.dto.RoleResponse;
import com.scaramutti.tms.workers.model.DriverProfileMode;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * Mapper de la capa Service del catalogo de roles. El objeto de respuesta es el mismo que
 * va embebido en el detalle de un trabajador: un solo lugar donde cambiarlo, y el
 * formulario de edicion conoce la modalidad de ficha del cargo actual sin pedir el catalogo.
 */
@Mapper(config = SharedMapperConfig.class)
public interface RoleServiceMapper {

    RoleResponse toRoleResponse(Role role);

    List<RoleResponse> toRoleResponseList(List<Role> roles);

    /** El texto de la columna al enum del modulo; un valor fuera del dominio revienta. */
    default DriverProfileMode toDriverProfileMode(String driverProfile) {
        return DriverProfileMode.fromColumn(driverProfile);
    }
}
