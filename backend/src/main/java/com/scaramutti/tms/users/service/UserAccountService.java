package com.scaramutti.tms.users.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.auth.service.PasswordService;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.UserAuditLog;
import com.scaramutti.tms.shared.repository.UserAuditLogRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.users.UsersError;
import com.scaramutti.tms.users.dto.UserAccountDetailResponse;
import com.scaramutti.tms.users.dto.UserAccountSummaryResponse;
import com.scaramutti.tms.users.dto.UserAccountTemporaryPasswordResponse;
import com.scaramutti.tms.users.mapper.UserAccountServiceMapper;
import com.scaramutti.tms.users.model.UserAuditChangeType;
import com.scaramutti.tms.users.model.UserAuditField;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Las cuentas del sistema. La lectura no abre transaccion, como los listados de trabajadores: el
 * filtro y el orden los resuelve el repositorio, y la forma de la respuesta, el mapper.
 */
@ApplicationScoped
public class UserAccountService {

    @Inject UserRepository userRepository;
    @Inject UserAuditLogRepository userAuditLogRepository;
    @Inject UserAccountServiceMapper userAccountServiceMapper;
    @Inject UserAccountRankPolicy userAccountRankPolicy;
    @Inject UserAccountWriteLock userAccountWriteLock;
    @Inject TemporaryPasswordGenerator temporaryPasswordGenerator;
    @Inject PasswordService passwordService;
    @Inject CurrentUser currentUser;

    /** Las activas, las inactivas o, con {@code isActive} nulo, todas, por nombre de usuario. */
    public List<UserAccountSummaryResponse> listUserAccounts(Boolean isActive) {
        return userAccountServiceMapper.toUserAccountSummaryResponseList(userRepository.listAccounts(isActive));
    }

    /** La cuenta con ese id, activa o inactiva; si no existe, {@code USR-001}. */
    public UserAccountDetailResponse getUserAccount(Integer userId) {
        return userRepository.findDetailById(userId)
            .map(userAccountServiceMapper::toUserAccountDetailResponse)
            .orElseThrow(UsersError.NOT_FOUND::toException);
    }

    /**
     * Las guardas corren antes del corte idempotente: repetir sobre una cuenta fuera de rango es un
     * 403. Antes de tomar la fila no se carga nada, porque la toma rechaza una cuenta ya en memoria.
     */
    @Transactional
    public UserAccountDetailResponse deactivateUserAccount(Integer userId) {
        if (userRepository.count("id", userId) == 0) {
            throw UsersError.NOT_FOUND.toException();
        }
        userAccountRankPolicy.rejectDisabledActorBeforeLocking();
        userAccountRankPolicy.assertIsNotOwnAccount(userId);
        User account = userAccountWriteLock.lockThenFind(userId).orElseThrow(UsersError.NOT_FOUND::toException);
        userAccountRankPolicy.assertCanActOn(account.worker.role);
        if (!Boolean.TRUE.equals(account.isActive)) {
            return getUserAccount(userId);
        }

        Integer actorId = currentUser.requireId();
        userAccountWriteLock.writeTranslatingConflicts(userId, () -> {
            account.isActive = false;
            account.markChangedBy(actorId);
            userAuditLogRepository.persist(statusChange(userId, UserAuditChangeType.DEACTIVATED, true, false, actorId));
        });
        return getUserAccount(userId);
    }

    /**
     * Las reglas del trabajador corren antes del corte idempotente, como las guardas: una cuenta activa
     * sobre un trabajador de baja responde el 409 y no un 200 que la daria por utilizable.
     */
    @Transactional
    public UserAccountDetailResponse reactivateUserAccount(Integer userId) {
        if (userRepository.count("id", userId) == 0) {
            throw UsersError.NOT_FOUND.toException();
        }
        userAccountRankPolicy.rejectDisabledActorBeforeLocking();
        User account = userAccountWriteLock.lockThenFind(userId).orElseThrow(UsersError.NOT_FOUND::toException);
        userAccountRankPolicy.assertCanActOn(account.worker.role);
        if (!Boolean.TRUE.equals(account.worker.isActive)) {
            throw UsersError.WORKER_INACTIVE.toException();
        }
        if (!Boolean.TRUE.equals(account.worker.role.canLogin)) {
            throw UsersError.ROLE_CANNOT_LOGIN.toException();
        }
        if (Boolean.TRUE.equals(account.isActive)) {
            return getUserAccount(userId);
        }

        Integer actorId = currentUser.requireId();
        userAccountWriteLock.writeTranslatingConflicts(userId, () -> {
            account.isActive = true;
            // Sin la subida, un refresh guardado antes de la baja volveria a renovar.
            account.sessionVersion = account.sessionVersion + 1;
            account.markChangedBy(actorId);
            userAuditLogRepository.persist(statusChange(userId, UserAuditChangeType.REACTIVATED, false, true, actorId));
        });
        return getUserAccount(userId);
    }

    /** La temporal se hashea antes de tomar la fila: el BCrypt con la fila tomada retendria las otras escrituras. */
    @Transactional
    public UserAccountTemporaryPasswordResponse resetUserAccountPassword(Integer userId) {
        if (userRepository.count("id", userId) == 0) {
            throw UsersError.NOT_FOUND.toException();
        }
        userAccountRankPolicy.rejectDisabledActorBeforeLocking();
        userAccountRankPolicy.assertIsNotOwnAccount(userId);
        String temporaryPassword = temporaryPasswordGenerator.generate();
        String passwordHash = passwordService.hash(temporaryPassword);
        User account = userAccountWriteLock.lockThenFind(userId).orElseThrow(UsersError.NOT_FOUND::toException);
        userAccountRankPolicy.assertCanActOn(account.worker.role);

        Integer actorId = currentUser.requireId();
        userAccountWriteLock.writeTranslatingConflicts(userId, () -> {
            account.passwordHash = passwordHash;
            // Sin la subida, un refresh emitido con la clave reemplazada seguiria renovando.
            account.sessionVersion = account.sessionVersion + 1;
            account.markChangedBy(actorId);
            userAuditLogRepository.persist(passwordReset(userId, actorId));
        });
        return new UserAccountTemporaryPasswordResponse(getUserAccount(userId), temporaryPassword);
    }

    private static UserAuditLog statusChange(Integer userId, UserAuditChangeType changeType, boolean oldValue,
            boolean newValue, Integer actorId) {
        UserAuditLog log = new UserAuditLog();
        log.userId = userId;
        log.changeType = changeType.name();
        log.fieldName = UserAuditField.IS_ACTIVE.fieldName();
        log.fieldLabel = UserAuditField.IS_ACTIVE.label();
        log.oldValue = String.valueOf(oldValue);
        log.newValue = String.valueOf(newValue);
        log.changedBy = actorId;
        return log;
    }

    private static UserAuditLog passwordReset(Integer userId, Integer actorId) {
        UserAuditLog log = new UserAuditLog();
        log.userId = userId;
        log.changeType = UserAuditChangeType.PASSWORD_RESET.name();
        log.fieldName = UserAuditLog.PASSWORD_FIELD;
        log.fieldLabel = UserAuditLog.PASSWORD_LABEL;
        log.changedBy = actorId;
        return log;
    }
}
