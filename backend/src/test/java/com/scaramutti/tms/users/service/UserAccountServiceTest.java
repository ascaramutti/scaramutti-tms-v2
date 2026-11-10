package com.scaramutti.tms.users.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.auth.service.PasswordService;
import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.UserAuditLog;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.UserAuditLogRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.shared.repository.UserRepository.UserAccountDetailRow;
import com.scaramutti.tms.users.UsersError;
import com.scaramutti.tms.users.dto.UserAccountDetailResponse;
import com.scaramutti.tms.users.dto.UserAccountTemporaryPasswordResponse;
import com.scaramutti.tms.users.mapper.UserAccountServiceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    private static final int ACCOUNT_ID = 42;
    private static final int ACTOR_ID = 7;

    @Mock UserRepository userRepository;
    @Mock UserAuditLogRepository userAuditLogRepository;
    @Mock UserAccountServiceMapper userAccountServiceMapper;
    @Mock UserAccountRankPolicy userAccountRankPolicy;
    @Mock UserAccountWriteLock userAccountWriteLock;
    @Mock CurrentUser currentUser;
    @Mock TemporaryPasswordGenerator temporaryPasswordGenerator;
    @Mock PasswordService passwordService;

    @InjectMocks UserAccountService userAccountService;

    private User account;

    @BeforeEach
    void setUp() {
        Role role = new Role();
        role.name = "sales";
        Worker worker = new Worker();
        worker.role = role;
        account = new User();
        account.id = ACCOUNT_ID;
        account.passwordHash = "hash-vieja";
        account.sessionVersion = 4;
        account.isActive = true;
        account.worker = worker;
        when(userRepository.count("id", ACCOUNT_ID)).thenReturn(1L);
    }

    @Test
    void resetUserAccountPassword_hashesBeforeTakingTheRow_andWritesEverythingInsideTheTranslator() {
        UserAccountDetailRow row = new UserAccountDetailRow(ACCOUNT_ID, "ztestcta", null, null, true, null, null, null,
            null);
        UserAccountDetailResponse detail = new UserAccountDetailResponse(ACCOUNT_ID, "ztestcta", null, null, true, null,
            null, null, null);
        when(temporaryPasswordGenerator.generate()).thenReturn("tmp-plain");
        when(passwordService.hash("tmp-plain")).thenReturn("tmp-hash");
        when(userAccountWriteLock.lockThenFind(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(currentUser.requireId()).thenReturn(ACTOR_ID);
        when(userRepository.findDetailById(ACCOUNT_ID)).thenReturn(Optional.of(row));
        when(userAccountServiceMapper.toUserAccountDetailResponse(row)).thenReturn(detail);
        AtomicBoolean insideTheTranslator = new AtomicBoolean();
        AtomicReference<String> writtenInside = new AtomicReference<>();
        doAnswer(call -> {
            assertEquals("hash-vieja|4|null", account.passwordHash + "|" + account.sessionVersion + "|"
                + account.updatedBy, "nada se escribe antes del traductor");
            insideTheTranslator.set(true);
            ((Runnable) call.getArgument(1)).run();
            writtenInside.set(account.passwordHash + "|" + account.sessionVersion + "|" + account.updatedBy);
            insideTheTranslator.set(false);
            return null;
        }).when(userAccountWriteLock).writeTranslatingConflicts(eq(ACCOUNT_ID), any(Runnable.class));
        doAnswer(call -> {
            assertTrue(insideTheTranslator.get(), "el INSERT del rastro sale al persistirlo: dentro del traductor");
            return null;
        }).when(userAuditLogRepository).persist(any(UserAuditLog.class));

        UserAccountTemporaryPasswordResponse result = userAccountService.resetUserAccountPassword(ACCOUNT_ID);

        assertSame(detail, result.account());
        assertEquals("tmp-plain", result.temporaryPassword());
        assertEquals("tmp-hash|5|" + ACTOR_ID, writtenInside.get(), "el hash, la version y la firma, en el traductor");
        ArgumentCaptor<UserAuditLog> trail = ArgumentCaptor.forClass(UserAuditLog.class);
        verify(userAuditLogRepository).persist(trail.capture());
        UserAuditLog log = trail.getValue();
        assertEquals(ACCOUNT_ID + "|PASSWORD_RESET|password|Contraseña|null|null|" + ACTOR_ID, log.userId + "|"
            + log.changeType + "|" + log.fieldName + "|" + log.fieldLabel + "|" + log.oldValue + "|" + log.newValue
            + "|" + log.changedBy);
        // El BCrypt antes de tomar la fila: con ella tomada, una tanda de restablecimientos frena las otras escrituras.
        InOrder order = inOrder(userAccountRankPolicy, passwordService, userAccountWriteLock, userRepository);
        order.verify(userAccountRankPolicy).assertIsNotOwnAccount(ACCOUNT_ID);
        order.verify(passwordService).hash("tmp-plain");
        order.verify(userAccountWriteLock).lockThenFind(ACCOUNT_ID);
        order.verify(userAccountRankPolicy).assertCanActOn(account.worker.role);
        order.verify(userAccountWriteLock).writeTranslatingConflicts(eq(ACCOUNT_ID), any(Runnable.class));
        order.verify(userRepository).findDetailById(ACCOUNT_ID);
    }

    @Test
    void resetUserAccountPassword_ofTheOwnAccount_throwsWithoutGeneratingNorTakingTheRow() {
        // Si la toma corriera antes, encontraria la cuenta: el 403 seria el mismo y solo never() lo distingue.
        lenient().when(userAccountWriteLock.lockThenFind(ACCOUNT_ID)).thenReturn(Optional.of(account));
        doThrow(UsersError.OWN_ACCOUNT.toException()).when(userAccountRankPolicy).assertIsNotOwnAccount(ACCOUNT_ID);

        ApiException ex = assertThrows(ApiException.class,
            () -> userAccountService.resetUserAccountPassword(ACCOUNT_ID));

        assertEquals("USR-007", ex.code());
        verify(userAccountWriteLock, never()).lockThenFind(anyInt());
        verify(passwordService, never()).hash(anyString());
        verifyNoInteractions(temporaryPasswordGenerator, userAuditLogRepository);
    }

    @Test
    void resetUserAccountPassword_outOfRank_throwsWithoutWriting() {
        when(temporaryPasswordGenerator.generate()).thenReturn("tmp-plain");
        when(userAccountWriteLock.lockThenFind(ACCOUNT_ID)).thenReturn(Optional.of(account));
        doThrow(UsersError.OUT_OF_RANK.toException()).when(userAccountRankPolicy).assertCanActOn(account.worker.role);

        ApiException ex = assertThrows(ApiException.class,
            () -> userAccountService.resetUserAccountPassword(ACCOUNT_ID));

        assertEquals("USR-008", ex.code());
        verify(userAccountWriteLock, never()).writeTranslatingConflicts(anyInt(), any(Runnable.class));
        verifyNoInteractions(userAuditLogRepository);
        assertEquals("hash-vieja|4", account.passwordHash + "|" + account.sessionVersion);
    }
}
