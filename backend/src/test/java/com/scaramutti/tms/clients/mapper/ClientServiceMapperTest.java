package com.scaramutti.tms.clients.mapper;

import com.scaramutti.tms.clients.service.cmd.UpdateClientCommand;
import com.scaramutti.tms.shared.entity.Client;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit del ClientServiceMapper, en particular de {@code applyUpdate}.
 *
 * <p>Por qué hace falta aparte de los tests de integración: desde que la columna del activo es
 * no actualizable, escribirla EN MEMORIA durante una edición dejó de tener efecto observable
 * desde HTTP, porque el UPDATE no la lleva y la respuesta se relee de la fila. Medido el
 * 2026-09-15: copiarle a {@code applyUpdate} la línea del alta que fija el activo en verdadero
 * dejaba la suite entera en verde.
 *
 * <p>Que no se note hoy no lo vuelve inofensivo. Son dos guardas distintas y a distinta altura:
 * el mapeo protege el objeto en memoria y la columna protege la fila. Si un día alguien necesita
 * que la columna vuelva a ser actualizable (la desactivación, por ejemplo), la única red que
 * quedaría es ésta.
 */
class ClientServiceMapperTest {

    private final ClientServiceMapper mapper = Mappers.getMapper(ClientServiceMapper.class);

    @Test
    void applyUpdate_writesTheFourEditableFields() {
        Client client = clienteDeBase();

        mapper.applyUpdate(client, new UpdateClientCommand(
            "ACME CORP SAC", "20123456789", "987654321", "Ana Rios"));

        assertEquals("ACME CORP SAC", client.name);
        assertEquals("20123456789", client.ruc);
        assertEquals("987654321", client.phone);
        assertEquals("Ana Rios", client.contactName);
    }

    @Test
    void applyUpdate_leavesTheServerOwnedFieldsUntouched() {
        Client client = clienteDeBase();
        Integer idOriginal = client.id;
        OffsetDateTime creadoOriginal = client.createdAt;

        mapper.applyUpdate(client, new UpdateClientCommand(
            "ACME CORP SAC", "20123456789", null, null));

        assertFalse(client.isActive, "la edición no puede reactivar a un cliente desactivado");
        assertEquals(idOriginal, client.id, "el id identifica la fila: la edición no lo toca");
        assertEquals(creadoOriginal, client.createdAt, "la fecha de creación no se edita");
    }

    /** Cliente DESACTIVADO a propósito: con uno activo, reactivarlo no se notaría. */
    private Client clienteDeBase() {
        Client client = new Client();
        client.id = 77;
        client.name = "ZMAPPER VIEJO";
        client.ruc = "20999999999";
        client.phone = "911111111";
        client.contactName = "Contacto Viejo";
        client.isActive = false;
        client.createdAt = OffsetDateTime.parse("2020-01-01T00:00:00Z");
        return client;
    }
}
