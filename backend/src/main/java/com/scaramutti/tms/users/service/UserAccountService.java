package com.scaramutti.tms.users.service;

import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.users.UsersError;
import com.scaramutti.tms.users.dto.UserAccountDetailResponse;
import com.scaramutti.tms.users.dto.UserAccountSummaryResponse;
import com.scaramutti.tms.users.mapper.UserAccountServiceMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Las cuentas del sistema. La lectura no abre transaccion, como los listados de trabajadores: el
 * filtro y el orden los resuelve el repositorio, y la forma de la respuesta, el mapper.
 */
@ApplicationScoped
public class UserAccountService {

    @Inject UserRepository userRepository;
    @Inject UserAccountServiceMapper userAccountServiceMapper;

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
}
