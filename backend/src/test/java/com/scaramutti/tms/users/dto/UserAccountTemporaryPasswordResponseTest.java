package com.scaramutti.tms.users.dto;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAccountTemporaryPasswordResponseTest {

    @Test
    void toString_keepsTheAccountButNotTheTemporaryPassword() {
        UserAccountDetailResponse account = new UserAccountDetailResponse(12, "ztestjperez", null, null, true,
            OffsetDateTime.parse("2026-11-03T14:20:00Z"), null, OffsetDateTime.parse("2026-11-03T14:20:00Z"), null);

        String printed = new UserAccountTemporaryPasswordResponse(account, "Kq7mXp3t").toString();

        assertFalse(printed.contains("Kq7mXp3t"), printed);
        assertTrue(printed.contains("ztestjperez"), printed);
    }
}
