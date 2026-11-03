package com.scaramutti.tms.users.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.repository.UserRepository.UserAccountDetailRow;
import com.scaramutti.tms.shared.repository.UserRepository.UserAccountRoleRow;
import com.scaramutti.tms.shared.repository.UserRepository.UserAccountRow;
import com.scaramutti.tms.shared.repository.UserRepository.UserAccountWorkerDetailRow;
import com.scaramutti.tms.shared.repository.UserRepository.UserAccountWorkerRow;
import com.scaramutti.tms.shared.repository.WorkerRepository.UserRefRow;
import com.scaramutti.tms.users.dto.UserAccountAuthorRef;
import com.scaramutti.tms.users.dto.UserAccountDetailResponse;
import com.scaramutti.tms.users.dto.UserAccountRoleResponse;
import com.scaramutti.tms.users.dto.UserAccountSummaryResponse;
import com.scaramutti.tms.users.dto.UserAccountWorkerDetailRef;
import com.scaramutti.tms.users.dto.UserAccountWorkerRef;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * Mapper de la capa Service: las filas de la consulta a la respuesta. Los sub-objetos se declaran a
 * mano para que el compilador verifique cada record por separado.
 */
@Mapper(config = SharedMapperConfig.class)
public interface UserAccountServiceMapper {

    UserAccountSummaryResponse toUserAccountSummaryResponse(UserAccountRow userAccountRow);

    List<UserAccountSummaryResponse> toUserAccountSummaryResponseList(List<UserAccountRow> userAccountRows);

    UserAccountWorkerRef toUserAccountWorkerRef(UserAccountWorkerRow userAccountWorkerRow);

    UserAccountRoleResponse toUserAccountRoleResponse(UserAccountRoleRow userAccountRoleRow);

    UserAccountDetailResponse toUserAccountDetailResponse(UserAccountDetailRow userAccountDetailRow);

    UserAccountWorkerDetailRef toUserAccountWorkerDetailRef(UserAccountWorkerDetailRow userAccountWorkerDetailRow);

    UserAccountAuthorRef toUserAccountAuthorRef(UserRefRow userRefRow);
}
