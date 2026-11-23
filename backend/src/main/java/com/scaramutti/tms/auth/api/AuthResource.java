package com.scaramutti.tms.auth.api;

import com.scaramutti.tms.auth.dto.ChangePasswordRequest;
import com.scaramutti.tms.auth.dto.LoginRequest;
import com.scaramutti.tms.auth.dto.LoginResponse;
import com.scaramutti.tms.auth.dto.RefreshRequest;
import com.scaramutti.tms.auth.dto.SessionUserResponse;
import com.scaramutti.tms.auth.mapper.AuthResourceMapper;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.auth.service.AuthService;
import com.scaramutti.tms.shared.util.CacheHeaders;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject AuthService authService;
    @Inject AuthResourceMapper authResourceMapper;
    @Inject CurrentUser currentUser;

    @POST
    @Path("/login")
    @PermitAll
    public RestResponse<LoginResponse> login(@Valid @NotNull LoginRequest loginRequest) {
        LoginResponse loginResponse = authService.login(authResourceMapper.toLoginCommand(loginRequest));
        return CacheHeaders.tokensNotStored(RestResponse.ResponseBuilder.ok(loginResponse));
    }

    @POST
    @Path("/refresh")
    @PermitAll
    public RestResponse<LoginResponse> refresh(@Valid @NotNull RefreshRequest refreshRequest) {
        LoginResponse loginResponse = authService.refresh(authResourceMapper.toRefreshCommand(refreshRequest));
        return CacheHeaders.tokensNotStored(RestResponse.ResponseBuilder.ok(loginResponse));
    }

    /**
     * La guarda va en el metodo y no en la clase porque {@code login} y {@code refresh} tienen
     * que seguir siendo publicos. Exige sesion tambien en el codigo y no solo en la policy por
     * ruta de application.properties: esa policy se evalua sobre la URL tal como llega, y hay
     * avisos publicados de rutas que la esquivan escribiendo el mismo camino con punto y coma o
     * con barras codificadas. La comprobacion del codigo no depende de como se escriba la ruta.
     */
    @POST
    @Path("/change-password")
    @Authenticated
    public RestResponse<LoginResponse> changePassword(@Valid @NotNull ChangePasswordRequest changePasswordRequest) {
        LoginResponse loginResponse = authService.changePassword(
            authResourceMapper.toChangePasswordCommand(currentUser.requireId(), changePasswordRequest));
        return CacheHeaders.callerTokensNotStored(RestResponse.ResponseBuilder.ok(loginResponse));
    }

    /** Misma segunda capa que {@code changePassword}: ver el comentario de ese metodo. */
    @GET
    @Path("/me")
    @Authenticated
    public RestResponse<SessionUserResponse> me() {
        SessionUserResponse sessionUserResponse = authService.findUserById(currentUser.requireId());
        return CacheHeaders.notStored(RestResponse.ResponseBuilder.ok(sessionUserResponse));
    }
}
