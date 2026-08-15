package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.service.UserLookup;
import com.scaramutti.tms.operations.mapper.ServiceServiceMapper;
import com.scaramutti.tms.shared.entity.CargoType;
import com.scaramutti.tms.shared.entity.Client;
import com.scaramutti.tms.shared.entity.Currency;
import com.scaramutti.tms.shared.entity.Service;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.CargoTypeRepository;
import com.scaramutti.tms.shared.repository.ClientRepository;
import com.scaramutti.tms.shared.repository.CurrencyRepository;
import com.scaramutti.tms.shared.repository.ServiceAssignmentRepository;
import com.scaramutti.tms.shared.repository.ServiceAssignmentRepository.ServiceAdditionalResourceRow;
import com.scaramutti.tms.shared.repository.ServiceEventRepository;
import com.scaramutti.tms.shared.repository.ServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.scaramutti.tms.operations.dto.ServiceAdditionalResourceResponse;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Unit del detalle, para la rama que la INTEGRACIÓN no puede montar: los tres catálogos del viaje
 * tienen clave foránea real en la base, así que no hay forma de dejar una huérfana por SQL (el
 * motor rechaza el UPDATE). La guarda existe igual, y este es el único lugar donde se puede fijar
 * su comportamiento.
 *
 * Por qué existe la guarda si la base ya lo impide: el cutover carga datos históricos por fuera de
 * la aplicación, y ese es exactamente el escenario donde una carga con las restricciones relajadas
 * dejaría filas apuntando a la nada. Con la guarda eso es un error del servidor, ruidoso y
 * rastreable; sin ella, el detalle serviría un cliente en null que el contrato declara imposible.
 */
class GetServiceServiceTest {

    private final ServiceRepository serviceRepository = mock(ServiceRepository.class);
    private final ServiceEventRepository serviceEventRepository = mock(ServiceEventRepository.class);
    private final ServiceAssignmentRepository serviceAssignmentRepository =
        mock(ServiceAssignmentRepository.class);
    private final ClientRepository clientRepository = mock(ClientRepository.class);
    private final CargoTypeRepository cargoTypeRepository = mock(CargoTypeRepository.class);
    private final CurrencyRepository currencyRepository = mock(CurrencyRepository.class);
    private final UserLookup userLookup = mock(UserLookup.class);
    private final ServicePriceVisibility priceVisibility = mock(ServicePriceVisibility.class);

    private GetServiceService getServiceService;

    @BeforeEach
    void setUp() {
        getServiceService = new GetServiceService();
        getServiceService.serviceRepository = serviceRepository;
        getServiceService.serviceEventRepository = serviceEventRepository;
        getServiceService.serviceAssignmentRepository = serviceAssignmentRepository;
        getServiceService.clientRepository = clientRepository;
        getServiceService.cargoTypeRepository = cargoTypeRepository;
        getServiceService.currencyRepository = currencyRepository;
        getServiceService.userLookup = userLookup;
        getServiceService.servicePriceVisibility = priceVisibility;
        getServiceService.serviceServiceMapper = Mappers.getMapper(ServiceServiceMapper.class);

        when(serviceRepository.findById(any())).thenReturn(aService());
        when(serviceEventRepository.listByServiceIdOrderedByCreatedAt(1L)).thenReturn(List.of());
        when(serviceAssignmentRepository.listByServiceId(1L)).thenReturn(List.of());
        // Los casos huerfanos de abajo revientan antes de llegar aca, pero los del camino
        // feliz no: sin este doble, el NPE taparia lo que de verdad se esta midiendo.
        when(serviceRepository.findAssignedResources(1L)).thenReturn(
            new ServiceRepository.ServiceAssignedResourcesRow(
                null, null, null, null, null, null));
        when(userLookup.requireAllById(any())).thenAnswer(invocation -> {
            List<Integer> requested = invocation.getArgument(0);
            Map<Integer, UserResponse> found = new LinkedHashMap<>();
            for (Integer id : requested) {
                found.put(id, new UserResponse(id, "ztest" + id, "ZTEST User", "dev", "admin", true));
            }
            return found;
        });
        when(clientRepository.findById(any())).thenReturn(new Client());
        when(cargoTypeRepository.findById(any())).thenReturn(new CargoType());
        when(currencyRepository.findById(any())).thenReturn(aCurrency());
        when(priceVisibility.includePrices()).thenReturn(true);
    }

    /**
     * El contrato declara la lista OBLIGATORIA, así que un viaje sin refuerzos la trae VACÍA. En
     * null, cualquier cliente que la recorra revienta en el primer viaje que no tuvo relevos, que
     * son casi todos.
     */
    @Test
    void getService_withoutReinforcements_returnsAnEmptyListNotNull() {
        assertEquals(List.of(), getServiceService.getService(1L).additionalResources());
    }

    /** Los refuerzos salen en el orden en que se sumaron: la lista es una línea de tiempo. */
    @Test
    void getService_withReinforcements_keepsTheOrderTheQueryReturns() {
        when(serviceAssignmentRepository.listByServiceId(1L)).thenReturn(List.of(
            aReinforcementRow(10L, 4, "Primero"), aReinforcementRow(11L, 5, "Segundo")));

        List<ServiceAdditionalResourceResponse> resources =
            getServiceService.getService(1L).additionalResources();

        assertEquals(List.of(10L, 11L), resources.stream()
            .map(ServiceAdditionalResourceResponse::id).toList());
    }

    /** Una fila con un solo recurso deja los otros dos refs en null, no en un objeto vacío. */
    @Test
    void getService_withAReinforcementOfASingleKind_leavesTheOtherTwoRefsNull() {
        when(serviceAssignmentRepository.listByServiceId(1L))
            .thenReturn(List.of(aReinforcementRow(10L, 4, "Relevo")));

        ServiceAdditionalResourceResponse resource =
            getServiceService.getService(1L).additionalResources().get(0);

        assertNotNull(resource.driver());
        assertNull(resource.tractor());
        assertNull(resource.trailer());
    }

    /**
     * Los autores de los refuerzos entran en el MISMO lote que los de la bitácora.
     *
     * <p>Sin este caso el cambio es indetectable: {@code summaryOf} cae de vuelta a una consulta
     * por usuario cuando el id no está en el lote, así que quitando el lote la respuesta sale
     * IDÉNTICA byte por byte y lo único que aparece es un N+1 silencioso — un viaje con doce
     * refuerzos pasaría de una consulta de usuarios a trece, con la suite entera en verde.
     */
    @Test
    void getService_asksForTheReinforcementAuthorsInTheSameBatch() {
        // assignedBy=9 y no el creador del viaje: con el mismo usuario, su id ya viajaria en el
        // lote por la bitacora y el caso pasaria aunque el concat no existiera.
        when(serviceAssignmentRepository.listByServiceId(1L)).thenReturn(List.of(
            new ServiceAdditionalResourceRow(10L, 4, "ZTEST Conductor", null, null, null, null,
                "Relevo", 9, OffsetDateTime.parse("2026-07-01T10:00:00Z"))));

        getServiceService.getService(1L);

        // El lote responde CON LO QUE LE PIDIERON: asi, si alguien quita el concat, el autor del
        // refuerzo no vuelve en el mapa, summaryOf cae al camino de a uno y el never() lo caza.
        // Con un mapa fijo el caso seria decorativo: pasaria con y sin el cambio.
        verify(userLookup).requireAllById(argThat(ids -> ids.contains(9)));
        // El never() es sobre EL AUTOR DEL REFUERZO, no sobre cualquier consulta suelta: el creador
        // del viaje si cae al camino de a uno cuando no escribio ninguna linea de bitacora, y eso
        // es correcto. Pedir never() a secas convertiria el caso en uno que mide otra cosa.
        verify(userLookup, never()).require(9);
    }

    private static ServiceAdditionalResourceRow aReinforcementRow(
            long id, int driverId, String reason) {
        return new ServiceAdditionalResourceRow(id, driverId, "ZTEST Conductor",
            null, null, null, null, reason, 1, OffsetDateTime.parse("2026-07-01T10:00:00Z"));
    }

    @Test
    void getService_whenTheClientVanished_failsWithAServerError() {
        when(clientRepository.findById(any())).thenReturn(null);

        assertEquals(500, assertThrows(ApiException.class,
            () -> getServiceService.getService(1L)).status());
    }

    @Test
    void getService_whenTheCargoTypeVanished_failsWithAServerError() {
        when(cargoTypeRepository.findById(any())).thenReturn(null);

        assertEquals(500, assertThrows(ApiException.class,
            () -> getServiceService.getService(1L)).status());
    }

    @Test
    void getService_whenTheCurrencyVanished_failsWithAServerError() {
        when(currencyRepository.findById(any())).thenReturn(null);

        assertEquals(500, assertThrows(ApiException.class,
            () -> getServiceService.getService(1L)).status());
    }

    /**
     * Y el fallo NO depende del rol: al despacho el precio no le viaja, pero si la moneda se
     * resolviera solo cuando se muestra, la MISMA fila rota daría 500 a unos y 200 a otros.
     */
    @Test
    void getService_whenTheCurrencyVanished_failsForTheDispatcherToo() {
        when(priceVisibility.includePrices()).thenReturn(false);
        when(currencyRepository.findById(any())).thenReturn(null);

        assertEquals(500, assertThrows(ApiException.class,
            () -> getServiceService.getService(1L)).status());
    }

    /** Un id que no existe es 404 del módulo, no un error del servidor. */
    @Test
    void getService_withUnknownId_failsWithTheModuleNotFound() {
        when(serviceRepository.findById(any())).thenReturn(null);

        ApiException exception = assertThrows(ApiException.class,
            () -> getServiceService.getService(1L));

        assertEquals(404, exception.status());
        assertEquals("OPS-005", exception.code());
    }

    private static Service aService() {
        Service service = new Service();
        service.id = 1L;
        service.code = "SRV-0001";
        service.clientId = 7;
        service.cargoTypeId = 3;
        service.currencyId = 1;
        service.origin = "Piura";
        service.destination = "Lima";
        service.tripScope = "PROVINCIA";
        service.status = "PENDING_ASSIGNMENT";
        service.weight = new BigDecimal("12000");
        service.price = new BigDecimal("3200");
        service.createdBy = 1;
        return service;
    }

    private static Currency aCurrency() {
        Currency currency = new Currency();
        currency.code = "PEN";
        return currency;
    }
}
