package com.scaramutti.tms.workers;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkersErrorTest {

    /** Un cliente viejo puede tener el código traducido con el texto del rechazo que ya no existe. */
    @Test
    void theRetiredCodeOfTheRoleWithoutLogin_isNotReused() {
        assertFalse(Arrays.stream(WorkersError.values()).anyMatch(error -> "WRK-011".equals(error.code())));
    }
}
