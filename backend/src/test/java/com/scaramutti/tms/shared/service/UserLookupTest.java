package com.scaramutti.tms.shared.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.mapper.AuthServiceMapper;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit del helper compartido de resolucion de usuarios. Cubre el contrato que
 * antes estaba disperso en cada copia de loadUser: single-id con orphan →
 * COM-500, y batch (1 query, dedup, indexado por id).
 */
@ExtendWith(MockitoExtension.class)
class UserLookupTest {

    @Mock UserRepository userRepository;
    @Mock AuthServiceMapper authServiceMapper;
    @InjectMocks UserLookup userLookup;

    private User user(int id) {
        User u = new User();
        u.id = id;
        return u;
    }

    private UserResponse response(int id) {
        return new UserResponse(id, "user" + id, "Nombre", "Cargo", "user" + id, true);
    }

    // ---------- require (single) -------------------------------------------------

    @Test
    void require_existingUser_returnsMappedResponse() {
        UserResponse expected = response(42);
        when(userRepository.findById(42)).thenReturn(user(42));
        when(authServiceMapper.toUserResponse(any(User.class))).thenReturn(expected);

        assertSame(expected, userLookup.require(42));
    }

    @Test
    void require_orphanFk_throwsInternalError_messageIncludesId() {
        when(userRepository.findById(99)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class, () -> userLookup.require(99));

        assertEquals("COM-500", ex.code());
        assertEquals(500, ex.status());
        assertTrue(ex.getMessage().contains("99"),
            "El message debe incluir el userId huerfano para soporte");
        verifyNoInteractions(authServiceMapper);
    }

    // ---------- requireAllById (batch) -------------------------------------------

    @Test
    void requireAllById_returnsMapIndexedById() {
        // Cast a (Object): fuerza el overload varargs list(String, Object...) que
        // usa UserLookup (evita la ambiguedad con list(String, Map/Parameters)).
        when(userRepository.list("id IN ?1", (Object) java.util.Set.of(42, 99)))
            .thenReturn(List.of(user(42), user(99)));
        when(authServiceMapper.toUserResponse(any(User.class)))
            .thenAnswer(inv -> response(((User) inv.getArgument(0)).id));

        Map<Integer, UserResponse> result = userLookup.requireAllById(List.of(42, 99));

        assertEquals(2, result.size());
        assertEquals(42, result.get(42).id());
        assertEquals(99, result.get(99).id());
    }

    @Test
    void requireAllById_dedupsRepeatedIds_intoSingleQuery() {
        when(userRepository.list(eq("id IN ?1"), any(java.util.Set.class))).thenReturn(List.of(user(42)));
        when(authServiceMapper.toUserResponse(any(User.class))).thenReturn(response(42));

        userLookup.requireAllById(List.of(42, 42, 42));

        // 3 ids iguales → 1 sola query, con el Set deduplicado (1 elemento).
        verify(userRepository, times(1)).list("id IN ?1", (Object) java.util.Set.of(42));
    }

    @Test
    void requireAllById_orphanInBatch_throwsInternalError_listingMissingIds() {
        // 2 ids pedidos, la query devuelve solo 1 → el faltante es orphan FK:
        // COM-500 ruidoso con el id, no un createdBy=null silencioso.
        when(userRepository.list("id IN ?1", (Object) java.util.Set.of(42, 99)))
            .thenReturn(List.of(user(42)));
        when(authServiceMapper.toUserResponse(any(User.class))).thenReturn(response(42));

        ApiException ex = assertThrows(ApiException.class,
            () -> userLookup.requireAllById(List.of(42, 99)));

        assertEquals("COM-500", ex.code());
        assertEquals(500, ex.status());
        assertTrue(ex.getMessage().contains("99"),
            "El message debe listar el id huerfano para soporte");
    }

    @Test
    void requireAllById_emptyInput_returnsEmptyMap_withoutQuery() {
        Map<Integer, UserResponse> result = userLookup.requireAllById(List.of());

        assertTrue(result.isEmpty());
        verify(userRepository, never()).list(any(String.class), any(Object.class));
        verifyNoInteractions(authServiceMapper);
    }
}
