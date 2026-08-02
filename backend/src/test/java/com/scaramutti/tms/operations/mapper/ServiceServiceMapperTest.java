package com.scaramutti.tms.operations.mapper;

import com.scaramutti.tms.operations.dto.ServiceSummaryResponse;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.model.TripScope;
import com.scaramutti.tms.shared.repository.ServiceRepository.ServiceListRow;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit del mapeo de la fila del listado. Lo que se fija acá es la regla que decide qué ve cada
 * rol: con precios apagados, el precio y la moneda quedan en null y la anotación de inclusión
 * del DTO los deja fuera del JSON; el resto de los campos NO cambia por el rol.
 *
 * El mapper se instancia con la fábrica de MapStruct, no por CDI: este mapeo es una función
 * pura y no necesita levantar la aplicación.
 */
class ServiceServiceMapperTest {

    private final ServiceServiceMapper mapper = Mappers.getMapper(ServiceServiceMapper.class);

    private static ServiceListRow row() {
        return new ServiceListRow(
            42L, "SRV-0042", "Piura", "Lima",
            LocalDate.of(2026, 8, 20), "PROVINCIA", "PENDING_ASSIGNMENT",
            new BigDecimal("5800.00"), "PEN", OffsetDateTime.parse("2026-08-02T10:00:00Z"),
            7, "IPH SAC", "20123456789", "987654321", "Maria Rojas"
        );
    }

    @Test
    void toServiceSummaryResponse_withPrices_keepsPriceAndCurrency() {
        ServiceSummaryResponse response = mapper.toServiceSummaryResponse(row(), true);

        assertEquals(new BigDecimal("5800.00"), response.price());
        assertEquals("PEN", response.currencyCode());
    }

    @Test
    void toServiceSummaryResponse_withoutPrices_nullsPriceAndCurrency() {
        ServiceSummaryResponse response = mapper.toServiceSummaryResponse(row(), false);

        assertNull(response.price());
        assertNull(response.currencyCode());
    }

    /** Apagar los precios NO puede recortar nada más: el resto de la fila viaja igual. */
    @Test
    void toServiceSummaryResponse_withoutPrices_keepsEveryOtherField() {
        ServiceSummaryResponse response = mapper.toServiceSummaryResponse(row(), false);

        assertEquals(42L, response.id());
        assertEquals("SRV-0042", response.code());
        assertEquals("Piura", response.origin());
        assertEquals("Lima", response.destination());
        assertEquals(LocalDate.of(2026, 8, 20), response.tentativeDate());
        assertEquals(TripScope.PROVINCIA, response.tripScope());
        assertEquals(ServiceStatus.PENDING_ASSIGNMENT, response.status());
        assertEquals(OffsetDateTime.parse("2026-08-02T10:00:00Z"), response.createdAt());
        assertNotNull(response.client());
        assertEquals(7, response.client().id());
        assertEquals("IPH SAC", response.client().name());
        assertEquals("20123456789", response.client().ruc());
        assertEquals("987654321", response.client().phone());
        assertEquals("Maria Rojas", response.client().contactName());
    }

    /** El estado y el ámbito llegan como texto de la columna y salen como enum. */
    @Test
    void toServiceSummaryResponse_translatesTheColumnTextToItsEnum() {
        ServiceListRow deleted = new ServiceListRow(
            1L, "SRV-0001", "Lima", "Ica", LocalDate.of(2026, 1, 1), "LOCAL", "DELETED",
            BigDecimal.ONE, "USD", OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            1, "Cliente", "20000000001", null, null);

        ServiceSummaryResponse response = mapper.toServiceSummaryResponse(deleted, true);

        assertEquals(TripScope.LOCAL, response.tripScope());
        assertEquals(ServiceStatus.DELETED, response.status());
    }
}
