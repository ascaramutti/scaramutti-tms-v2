package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.shared.entity.Condition;
import com.scaramutti.tms.shared.entity.QuotationCondition;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.ConditionRepository;
import com.scaramutti.tms.shared.repository.QuotationConditionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit del QuotationConditionPersistenceService. Mockea ambos repos para aislar la logica de
 * validacion (Opcion B) y persistencia del acceso a BD.
 *
 * Cubre:
 *  - validate: null/vacia = no-op; todas activas = pasa; duplicados = COM-001 (fail-fast,
 *    sin consultar el catalogo); id inexistente = COM-001; id inactivo = QUO-007.
 *  - persist: null/vacia = no inserta; N ids = N filas en la junction.
 */
@ExtendWith(MockitoExtension.class)
class QuotationConditionPersistenceServiceTest {

    @Mock ConditionRepository conditionRepository;
    @Mock QuotationConditionRepository quotationConditionRepository;

    @InjectMocks QuotationConditionPersistenceService service;

    private static Condition condition(int id, boolean active) {
        Condition c = new Condition();
        c.id = id;
        c.text = "Condicion " + id;
        c.displayOrder = id;
        c.isActive = active;
        return c;
    }

    // ---------- validate -------------------------------------------------------

    @Test
    void validate_null_isNoOp() {
        service.validate(null);
        verifyNoInteractions(conditionRepository);
    }

    @Test
    void validate_empty_isNoOp() {
        service.validate(List.of());
        verifyNoInteractions(conditionRepository);
    }

    @Test
    void validate_allActive_passes() {
        when(conditionRepository.list(eq("id in ?1"), any(List.class)))
            .thenReturn(List.of(condition(1, true), condition(2, true)));

        assertDoesNotThrow(() -> service.validate(List.of(1, 2)));
    }

    @Test
    void validate_duplicateIds_throwsCOM001_withoutQueryingCatalog() {
        ApiException ex = assertThrows(ApiException.class, () -> service.validate(List.of(1, 1)));

        assertEquals("COM-001", ex.code());
        verifyNoInteractions(conditionRepository); // fail-fast antes de cargar el catalogo
    }

    @Test
    void validate_nonexistentId_throwsCOM001() {
        when(conditionRepository.list(eq("id in ?1"), any(List.class)))
            .thenReturn(List.of(condition(1, true))); // falta el id 2

        ApiException ex = assertThrows(ApiException.class, () -> service.validate(List.of(1, 2)));

        assertEquals("COM-001", ex.code());
    }

    @Test
    void validate_inactiveId_throwsQUO007() {
        when(conditionRepository.list(eq("id in ?1"), any(List.class)))
            .thenReturn(List.of(condition(1, true), condition(2, false)));

        ApiException ex = assertThrows(ApiException.class, () -> service.validate(List.of(1, 2)));

        assertEquals("QUO-007", ex.code());
    }

    // ---------- persist --------------------------------------------------------

    @Test
    void persist_null_insertsNothing() {
        service.persist(null, 5L);
        verifyNoInteractions(quotationConditionRepository);
    }

    @Test
    void persist_empty_insertsNothing() {
        service.persist(List.of(), 5L);
        verifyNoInteractions(quotationConditionRepository);
    }

    @Test
    void persist_ids_insertsOneRowPerId() {
        service.persist(List.of(3, 7), 5L);

        verify(quotationConditionRepository, times(2)).persist(any(QuotationCondition.class));
    }
}
