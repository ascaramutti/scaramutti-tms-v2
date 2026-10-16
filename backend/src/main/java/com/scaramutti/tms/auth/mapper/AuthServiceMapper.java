package com.scaramutti.tms.auth.mapper;

import com.scaramutti.tms.auth.dto.LoginResponse;
import com.scaramutti.tms.auth.dto.SessionUserResponse;
import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.model.AccessToken;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

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
@Mapper(config = SharedMapperConfig.class)
public interface AuthServiceMapper {

    /**
     * Compone UserResponse desde User + Worker (relacion @OneToOne EAGER) + el
     * cargo del trabajador, que da el rol y el cargo visible. El {@code fullName} usa el metodo
     * {@code Worker#fullName()} via expression (MapStruct no detecta metodos
     * sin prefijo {@code get}). Toda cuenta tiene trabajador: la columna
     * {@code users.worker_id} es obligatoria, unica y clave foranea.
     *
     * Este metodo es el {@code toUserResponse} canonico del proyecto;
     * otros services que necesiten convertir User → UserResponse inyectan
     * este mapper en vez de duplicar la logica.
     */
    @Mapping(target = "fullName", expression = "java(user.worker.fullName())")
    @Mapping(target = "position", source = "worker.role.description")
    @Mapping(target = "role",     source = "worker.role.name")
    UserResponse toUserResponse(User user);

    /**
     * El usuario de la sesion: el de {@link #toUserResponse} mas el id de su trabajador, otro dato
     * que el id de la cuenta aunque en algunas coincida. Repite sus tres reglas: MapStruct no se las
     * hereda a un destino de otro tipo (con {@code @InheritConfiguration} no compila) y el proyecto
     * no usa anotaciones compuestas. Si una cambia, cambia en los dos.
     */
    @Mapping(target = "fullName", expression = "java(user.worker.fullName())")
    @Mapping(target = "position", source = "worker.role.description")
    @Mapping(target = "role",     source = "worker.role.name")
    @Mapping(target = "workerId", source = "worker.id")
    SessionUserResponse toSessionUserResponse(User user);

    @Mapping(target = "expiresIn", source = "accessToken.expiresInSeconds")
    LoginResponse toLoginResponse(User user, AccessToken accessToken, String refreshToken);

    /** MapStruct detecta este metodo automaticamente para convertir Instant a OffsetDateTime en UTC. */
    default OffsetDateTime toUtcOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
