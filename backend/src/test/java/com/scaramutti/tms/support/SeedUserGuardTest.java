package com.scaramutti.tms.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeedUserGuardTest {

    @Test
    void seedUserFor_rejectsAnUppercaseUsername_beforeTheDatabase() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
            () -> new WarehouseTestData().seedUserFor(1, "ztestuserMay", "sales"));

        assertEquals("seedUserFor exige el nombre en minúsculas porque la base rechaza las mayúsculas;"
            + " recibió: ztestuserMay", rejected.getMessage());
    }
}
