package com.scaramutti.tms.workers.mapper;

import com.scaramutti.tms.sharedcatalogs.model.FleetResourceStatus;
import com.scaramutti.tms.workers.dto.WorkerDriverProfileRequest;
import com.scaramutti.tms.workers.dto.WorkerRequest;
import com.scaramutti.tms.workers.service.cmd.CreateWorkerCommand;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests del mapper de la capa REST del alta, sin contenedor.
 *
 * <p>Miden la normalizacion y, sobre todo, la GUARDA DE NULOS de la ficha: por HTTP se ve que
 * la respuesta sale sin ficha, pero no se ve si el servicio recibio un nulo o un objeto de
 * campos vacios, y de esa diferencia depende entera la regla de correspondencia con el cargo.
 */
class WorkerResourceMapperTest {

    private final WorkerResourceMapper mapper = Mappers.getMapper(WorkerResourceMapper.class);

    private WorkerRequest request(String firstName, String lastName, String documentNumber,
            String phone, String role, WorkerDriverProfileRequest driver) {
        return new WorkerRequest(firstName, lastName, 1, documentNumber, phone, role,
            LocalDate.of(2024, 3, 1), driver);
    }

    /**
     * Este es EL caso: el mapper devuelve objetos vacios en vez de nulos porque el listado lo
     * necesita, y esa estrategia alcanza tambien a los mapeos anidados. Sin la declaracion
     * explicita del metodo de la ficha, esto devolveria un objeto de campos vacios y la regla
     * de correspondencia quedaria invertida en los dos sentidos, con todo compilando.
     */
    @Test
    void anAbsentDriverProfile_arrivesAsNull_notAsAnEmptyObject() {
        CreateWorkerCommand command =
            mapper.toCreateWorkerCommand(request("Juan", "Pérez", "45678912", null, "operator", null));

        assertNull(command.driver(),
            "sin ficha en el cuerpo, el servicio tiene que recibir null y no un objeto vacio");
    }

    @Test
    void aPresentDriverProfile_arrivesTrimmed() {
        CreateWorkerCommand command = mapper.toCreateWorkerCommand(
            request("Juan", "Pérez", "45678912", null, "driver",
                new WorkerDriverProfileRequest("  Q12345678  ", "  A-IIIc  ", null)));

        assertNotNull(command.driver());
        assertEquals("Q12345678", command.driver().licenseNumber());
        assertEquals("A-IIIc", command.driver().licenseCategory());
        assertNull(command.driver().status(), "el estado ausente lo decide el servicio, no el mapper");
    }

    @Test
    void theDriverStatus_travelsUntouched() {
        CreateWorkerCommand command = mapper.toCreateWorkerCommand(
            request("Juan", "Pérez", "45678912", null, "driver",
                new WorkerDriverProfileRequest("Q12345678", null, FleetResourceStatus.MAINTENANCE)));

        assertEquals(FleetResourceStatus.MAINTENANCE, command.driver().status());
    }

    /** Recorta los bordes y NO cambia la caja: un nombre se guarda como lo escribieron. */
    @Test
    void theThreeNormalizedTexts_areTrimmedWithoutChangingCase() {
        CreateWorkerCommand command = mapper.toCreateWorkerCommand(
            request("  juan Carlos  ", " PÉREZ huamán ", "  45678912  ", null, "operator", null));

        assertEquals("juan Carlos", command.firstName());
        assertEquals("PÉREZ huamán", command.lastName());
        assertEquals("45678912", command.documentNumber());
    }

    /** Un texto de solo espacios llega NULO, que es lo que la guarda del servicio espera. */
    @Test
    void aTextOfOnlySpaces_arrivesAsNull() {
        CreateWorkerCommand command =
            mapper.toCreateWorkerCommand(request("   ", "Pérez", "45678912", null, "operator", null));

        assertNull(command.firstName());
    }

    /**
     * El telefono y el cargo viajan TAL CUAL, que es lo que declara el contrato: el formato del
     * telefono ya exige nueve digitos, y un cargo con espacios no es ninguna fila del catalogo.
     */
    @Test
    void thePhoneAndTheRole_travelUntouched() {
        CreateWorkerCommand command =
            mapper.toCreateWorkerCommand(request("Juan", "Pérez", "45678912", " 987654321 ", " operator ", null));

        assertEquals(" 987654321 ", command.phone());
        assertEquals(" operator ", command.role());
    }
}
