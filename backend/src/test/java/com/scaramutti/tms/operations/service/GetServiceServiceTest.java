package com.scaramutti.tms.operations.service;

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
import com.scaramutti.tms.shared.repository.ServiceEventRepository;
import com.scaramutti.tms.shared.repository.ServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        getServiceService.clientRepository = clientRepository;
        getServiceService.cargoTypeRepository = cargoTypeRepository;
        getServiceService.currencyRepository = currencyRepository;
        getServiceService.userLookup = userLookup;
        getServiceService.servicePriceVisibility = priceVisibility;
        getServiceService.serviceServiceMapper = Mappers.getMapper(ServiceServiceMapper.class);

        when(serviceRepository.findById(any())).thenReturn(aService());
        when(serviceEventRepository.listByServiceIdOrderedByCreatedAt(1L)).thenReturn(List.of());
        when(userLookup.requireAllById(any())).thenReturn(Map.of());
        when(clientRepository.findById(any())).thenReturn(new Client());
        when(cargoTypeRepository.findById(any())).thenReturn(new CargoType());
        when(currencyRepository.findById(any())).thenReturn(aCurrency());
        when(priceVisibility.includePrices()).thenReturn(true);
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
