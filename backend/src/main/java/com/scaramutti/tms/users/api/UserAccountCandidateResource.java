package com.scaramutti.tms.users.api;

import com.scaramutti.tms.shared.util.CacheHeaders;
import com.scaramutti.tms.users.dto.UserAccountCandidateResponse;
import com.scaramutti.tms.users.service.UserAccountService;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.List;

@Authenticated
@Path("/user-account-candidates")
@Produces(MediaType.APPLICATION_JSON)
public class UserAccountCandidateResource {

    @Inject UserAccountService userAccountService;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager"})
    public RestResponse<List<UserAccountCandidateResponse>> listUserAccountCandidates(
        @QueryParam("q")        @Size(min = 3, max = 200) String q,
        @QueryParam("workerId")                           Integer workerId
    ) {
        return CacheHeaders.notStored(
            RestResponse.ResponseBuilder.ok(userAccountService.listUserAccountCandidates(q, workerId)));
    }
}
