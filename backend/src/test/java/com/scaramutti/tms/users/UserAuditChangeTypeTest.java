package com.scaramutti.tms.users;

import com.scaramutti.tms.shared.entity.UserAuditLog;
import com.scaramutti.tms.users.model.UserAuditChangeType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El tipo del rastro de las cuentas en tres lugares: el enum del modulo, la constante de la sesion y el CHECK. */
@QuarkusTest
class UserAuditChangeTypeTest {

    @Inject EntityManager entityManager;

    @Test
    void theEnum_isExactlyTheCheckOfTheColumn_andHoldsTheConstantOfTheOwnChange() {
        Set<String> allowed = new TreeSet<>();
        Matcher quoted = Pattern.compile("'([A-Z_]+)'").matcher((String) entityManager.createNativeQuery(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_user_audit_change'")
            .getSingleResult());
        while (quoted.find()) {
            allowed.add(quoted.group(1));
        }

        assertEquals(allowed, Arrays.stream(UserAuditChangeType.values()).map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new)));
        assertTrue(allowed.contains(UserAuditLog.PASSWORD_CHANGED));
        assertEquals(UserAuditChangeType.PASSWORD_CHANGED.name(), UserAuditLog.PASSWORD_CHANGED);
    }
}
