package com.scaramutti.tms.auth.mapper;

import com.scaramutti.tms.auth.dto.ChangePasswordRequest;
import com.scaramutti.tms.auth.dto.LoginRequest;
import com.scaramutti.tms.auth.dto.RefreshRequest;
import com.scaramutti.tms.auth.service.cmd.ChangePasswordCommand;
import com.scaramutti.tms.auth.service.cmd.LoginCommand;
import com.scaramutti.tms.auth.service.cmd.RefreshCommand;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

/**
 * Mapper de la capa REST: traduce DTOs HTTP entrantes a Commands del service.
 * Lo inyecta AuthResource.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface AuthResourceMapper {

    LoginCommand toLoginCommand(LoginRequest loginRequest);

    RefreshCommand toRefreshCommand(RefreshRequest refreshRequest);

    ChangePasswordCommand toChangePasswordCommand(Integer userId, ChangePasswordRequest changePasswordRequest);
}
