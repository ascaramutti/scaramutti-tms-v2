package com.scaramutti.tms.auth.api;

import com.scaramutti.tms.auth.dto.ChangePasswordRequest;
import com.scaramutti.tms.auth.dto.LoginRequest;
import com.scaramutti.tms.auth.dto.LoginResponse;
import com.scaramutti.tms.auth.dto.RefreshRequest;
import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.mapper.AuthResourceMapper;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.auth.service.AuthService;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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
    public LoginResponse login(@Valid LoginRequest loginRequest) {
        return authService.login(authResourceMapper.toLoginCommand(loginRequest));
    }

    @POST
    @Path("/refresh")
    @PermitAll
    public LoginResponse refresh(@Valid RefreshRequest refreshRequest) {
        return authService.refresh(authResourceMapper.toRefreshCommand(refreshRequest));
    }

    @POST
    @Path("/change-password")
    public Response changePassword(@Valid ChangePasswordRequest changePasswordRequest) {
        authService.changePassword(authResourceMapper.toChangePasswordCommand(currentUser.requireId(), changePasswordRequest));
        return Response.noContent().build();
    }

    @GET
    @Path("/me")
    public UserResponse me() {
        return authService.findUserById(currentUser.requireId());
    }
}
