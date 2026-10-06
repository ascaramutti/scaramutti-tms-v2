package com.scaramutti.tms.clients.mapper;

import com.scaramutti.tms.clients.dto.ClientRequest;
import com.scaramutti.tms.clients.service.cmd.CreateClientCommand;
import com.scaramutti.tms.clients.service.cmd.UpdateClientCommand;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit del ClientResourceMapper, instanciado via {@link Mappers#getMapper} y no via CDI:
 * MapStruct genera la implementacion al compilar y el test la usa sin contenedor.
 *
 * Cubre las dos cosas que el javadoc del mapper AFIRMA y que por HTTP no se pueden ver:
 *  - la edicion normaliza igual que el alta (si difirieran, guardar un cliente sin tocarle
 *    nada le cambiaria los datos);
 *  - un request nulo devuelve un command de nulos y no un nulo, que es de lo que depende la
 *    guarda del service. Por REST es inalcanzable porque el borde contesta antes, asi que
 *    sin este caso la afirmacion no la medía nada.
 */
class ClientResourceMapperTest {

    private final ClientResourceMapper mapper = Mappers.getMapper(ClientResourceMapper.class);

    @Test
    void toUpdateClientCommand_normalizesLikeTheCreateOne() {
        ClientRequest request = new ClientRequest(
            "  acme corp sac  ", "20123456789", "987654321", "  Ana Rios  ");

        UpdateClientCommand edicion = mapper.toUpdateClientCommand(request);

        assertEquals("ACME CORP SAC", edicion.name(), "la razon social va en mayusculas y sin espacios");
        assertEquals("Ana Rios", edicion.contactName(), "el contacto es nombre de persona: se recorta, no se sube");
        assertEquals("20123456789", edicion.ruc(), "el ruc pasa tal cual");
        assertEquals("987654321", edicion.phone(), "el telefono pasa tal cual");

        CreateClientCommand alta = mapper.toCreateClientCommand(request);
        assertEquals(alta.name(), edicion.name(), "alta y edicion normalizan la razon social igual");
        assertEquals(alta.contactName(), edicion.contactName(), "alta y edicion normalizan el contacto igual");
    }

    @Test
    void toUpdateClientCommand_withBlankStrings_leavesThemNull() {
        UpdateClientCommand command = mapper.toUpdateClientCommand(
            new ClientRequest("   ", "20123456789", null, "   "));

        assertNull(command.name(), "una razon social de solo espacios llega nula al service");
        assertNull(command.contactName(), "un contacto de solo espacios llega nulo");
    }

    @Test
    void toUpdateClientCommand_withNullRequest_returnsACommandOfNulls() {
        UpdateClientCommand command = mapper.toUpdateClientCommand(null);

        assertNotNull(command, "devuelve un command, no un nulo: de eso depende la guarda del service");
        assertNull(command.name());
        assertNull(command.ruc());
        assertNull(command.phone());
        assertNull(command.contactName());
    }
}
