package com.scaramutti.tms.auth.mapper;

import com.scaramutti.tms.auth.dto.LoginResponse;
import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.model.AccessToken;
import com.scaramutti.tms.shared.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Mapper de la capa Service: traduce entidades del dominio a DTOs de salida.
 * Lo inyecta AuthService cuando arma respuestas para el cliente.
 *
 * Es PURO: data in, data out, sin side effects ni llamadas a otros services.
 * Por eso recibe los tokens ya generados como parametros (no los genera).
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface AuthServiceMapper {

    @Mapping(target = "fullName", expression = "java(user.worker.fullName())")
    @Mapping(target = "position", source = "worker.position")
    @Mapping(target = "role",     source = "role.name")
    UserResponse toUserResponse(User user);

    @Mapping(target = "expiresIn", source = "accessToken.expiresInSeconds")
    LoginResponse toLoginResponse(User user, AccessToken accessToken, String refreshToken);

    /** MapStruct detecta este metodo automaticamente para convertir Instant a OffsetDateTime en UTC. */
    default OffsetDateTime toUtcOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
