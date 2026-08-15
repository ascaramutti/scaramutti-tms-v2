package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.dto.ServiceResourceConflictResponse;
import com.scaramutti.tms.operations.model.ServiceResourceKind;
import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.shared.repository.ServiceResourceConflictRepository;
import com.scaramutti.tms.shared.repository.ServiceResourceConflictRepository.ServiceResourceConflictRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El armado del conflicto, medido con la consulta simulada.
 *
 * <p>Existe por un caso concreto: al extraer esta clase de la asignacion, el aplastado de textos
 * paso de {@code display} a {@code flattenLineBreaks}, y la diferencia es que el primero NOMBRA el
 * vacio y el segundo deja pasar el null. El nombre del conductor sale de un JOIN contra los
 * trabajadores del sistema anterior y puede venir nulo, asi que la regresion escribia
 * "el conductor null" en la bitacora, que es un rastro que despues nadie corrige.
 *
 * <p>Por HTTP ese caso es dificil de montar —la clave foranea impide dejar un conductor sin
 * trabajador—, y por eso la mutacion sobrevivia a la suite entera. Aca la consulta se simula y el
 * caso se vuelve alcanzable.
 */
class ServiceResourceConflictsTest {

    private ServiceResourceConflictRepository repository;
    private ServiceResourceConflicts conflicts;

    @BeforeEach
    void setUp() {
        repository = mock(ServiceResourceConflictRepository.class);
        conflicts = new ServiceResourceConflicts();
        conflicts.serviceResourceConflictRepository = repository;
    }

    private void queryReturns(ServiceResourceKind kind, String code, ServiceStatus status) {
        when(repository.findActiveConflicts(anyLong(), any(), any(), any()))
            .thenReturn(List.of(new ServiceResourceConflictRow(kind, code, status)));
    }

    @Test
    void find_withoutAName_namesTheEmptyInsteadOfWritingNull() {
        queryReturns(ServiceResourceKind.DRIVER, "SRV-0042", ServiceStatus.IN_PROGRESS);

        List<ServiceResourceConflictResponse> found =
            conflicts.find(1L, 7, 3, null, null, "ABC123", null);

        assertEquals("(vacío)", found.get(0).resourceName(),
            "un conductor sin nombre tiene que NOMBRARSE vacío, no escribirse como null");
        assertFalse(conflicts.asForcibleConflict(found).getMessage().contains("null"));
        // Los ids van a la consulta EN ORDEN. Con la consulta simulada por posición cualquiera, el
        // resto del test pinea los tres nombres y es ciego a una permutación de los tres ids, que
        // es el error que buscaría a otro viaje y contestaría que no hay conflicto.
        verify(repository).findActiveConflicts(1L, 7, 3, null);
    }

    @Test
    void find_flattensLineBreaksSoTheLogCannotGainFakeLines() {
        queryReturns(ServiceResourceKind.DRIVER, "SRV-0042", ServiceStatus.IN_PROGRESS);

        List<ServiceResourceConflictResponse> found =
            conflicts.find(1L, 7, 3, null, "Juan\nPrecio: 3200", "ABC123", null);

        assertFalse(found.get(0).resourceName().contains("\n"));
        assertFalse(conflicts.forcedLine(found.get(0)).contains("\n"),
            "la línea del forzado va a la bitácora, que tiene una línea por dato");
    }

    /** El texto concuerda por genero: "La carreta ... ya está asignadA". */
    @Test
    void theMessage_agreesInGenderWithTheResource() {
        queryReturns(ServiceResourceKind.TRAILER, "SRV-0042", ServiceStatus.PENDING_START);
        List<ServiceResourceConflictResponse> trailer =
            conflicts.find(1L, null, null, 5, null, null, "XYZ789");
        assertTrue(conflicts.asForcibleConflict(trailer).getMessage()
            .contains("La carreta XYZ789 ya está asignada"),
            conflicts.asForcibleConflict(trailer).getMessage());

        queryReturns(ServiceResourceKind.TRACTOR, "SRV-0042", ServiceStatus.PENDING_START);
        List<ServiceResourceConflictResponse> tractor =
            conflicts.find(1L, null, 3, null, null, "ABC123", null);
        assertTrue(conflicts.asForcibleConflict(tractor).getMessage()
            .contains("El tracto ABC123 ya está asignado"));
    }

    /**
     * Se cuentan RECURSOS distintos, no filas: el mismo conductor retenido por dos viajes son dos
     * filas y UN solo recurso en conflicto, y decir "hay 1 recurso más" ahí sería falso.
     */
    @Test
    void theDetail_countsDistinctResourcesAndNotRows() {
        when(repository.findActiveConflicts(anyLong(), any(), any(), any())).thenReturn(List.of(
            new ServiceResourceConflictRow(ServiceResourceKind.DRIVER, "SRV-0042", ServiceStatus.IN_PROGRESS),
            new ServiceResourceConflictRow(ServiceResourceKind.DRIVER, "SRV-0043", ServiceStatus.PENDING_START)));

        String message = conflicts.asForcibleConflict(
            conflicts.find(1L, 7, null, null, "Juan Pérez", null, null)).getMessage();

        assertFalse(message.contains("recurso más en conflicto"),
            "dos filas del MISMO conductor son un solo recurso: " + message);
    }

    /**
     * El aplastado de {@code forcedLine} NO se apoya en que el que llama ya lo haya hecho, y esa
     * defensa hay que medirla aparte: todos los demas casos le pasan un conflicto que salio de
     * {@code find}, o sea ya aplastado, y con eso el segundo aplastado se puede borrar sin que
     * nada se ponga rojo. Es un metodo publico sobre un record publico que cualquiera construye.
     */
    @Test
    void forcedLine_flattensEvenWhenTheRecordWasBuiltByHand() {
        ServiceResourceConflictResponse aMano = new ServiceResourceConflictResponse(
            ServiceResourceKind.DRIVER, "Juan\nEstado: en ruta → completado",
            "SRV-0042\nNota: nada", ServiceStatus.IN_PROGRESS);

        assertFalse(conflicts.forcedLine(aMano).contains("\n"),
            "la linea del forzado va a la bitacora, que tiene una linea por dato");
    }
}