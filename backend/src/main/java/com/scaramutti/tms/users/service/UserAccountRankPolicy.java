package com.scaramutti.tms.users.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.users.UsersError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * Quien escribe cuentas actua solo sobre las de un cargo de nivel estrictamente menor al suyo, y
 * {@code admin} esta exento. Es la regla de Trabajadores, copiada y no llamada porque esa emite sus
 * propios codigos. El nivel de quien escribe se lee de la base: el token puede traer un cargo viejo.
 */
@ApplicationScoped
public class UserAccountRankPolicy {

    private static final String EXEMPT_ROLE = "admin";

    /** La misma lista que el {@code @RolesAllowed} de cada escritura del recurso. */
    static final Set<String> WRITE_ROLES =
        Set.of("admin", "general_manager", "operations_manager", "finance_manager");

    @Inject CurrentUser currentUser;
    @Inject UserRepository userRepository;

    /** Antes de tomar la fila y sin cargar la cuenta: quien ya no escribe no la retiene hasta su 403. */
    public void rejectDisabledActorBeforeLocking() {
        if (!userRepository.isEnabledToWrite(currentUser.requireId(), WRITE_ROLES)) {
            throw CommonError.FORBIDDEN.toException();
        }
    }

    /** Nadie actua sobre su propia cuenta, tampoco {@code admin}: corre antes que el rango, que no dice por que. */
    public void assertIsNotOwnAccount(Integer accountId) {
        if (currentUser.requireId().equals(accountId)) {
            throw UsersError.OWN_ACCOUNT.toException();
        }
    }

    public void assertCanActOn(Role accountRole) {
        User actor = requireActor();
        if (EXEMPT_ROLE.equals(actor.worker.role.name)) {
            return;
        }
        if (accountRole.level >= actor.worker.role.level) {
            throw UsersError.OUT_OF_RANK.toException();
        }
    }

    /** Releido despues de tomar la fila: el corte de antes no carga la cuenta y puede quedar viejo. */
    private User requireActor() {
        return userRepository.findByIdOptional(currentUser.requireId())
            .filter(user -> Boolean.TRUE.equals(user.isActive))
            .filter(user -> Boolean.TRUE.equals(user.worker.isActive))
            .filter(user -> WRITE_ROLES.contains(user.worker.role.name))
            .orElseThrow(CommonError.FORBIDDEN::toException);
    }
}
