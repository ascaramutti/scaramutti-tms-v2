package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.dto.ServiceSummaryResponse;
import com.scaramutti.tms.operations.mapper.ServiceServiceMapper;
import com.scaramutti.tms.operations.service.cmd.ListServicesQuery;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.repository.ServiceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Listado de servicios de transporte. Solo lectura: el total y la pagina comparten el mismo
 * filtrado (y la pagina ni se consulta cuando empieza mas alla del total).
 *
 * <p>Los precios NO son visibles para todos: quien puede verlos lo decide
 * {@link ServicePriceVisibility}, compartido con el detalle para que la respuesta no cambie
 * segun por donde se pregunte.
 */
@ApplicationScoped
public class ListServicesService {

    @Inject ServiceRepository serviceRepository;
    @Inject ServiceServiceMapper serviceServiceMapper;
    @Inject ServicePriceVisibility servicePriceVisibility;

    /**
     * Transaccional aunque solo lea: el total y la pagina son dos consultas y asi comparten una
     * sola conexion, en vez de tomar y devolver dos.
     *
     * <p>OJO con lo que esto NO garantiza: con el aislamiento por defecto (READ COMMITTED) cada
     * sentencia toma su propio snapshot, asi que un alta o una asignacion concurrentes todavia puede hacer que el
     * total no calce exactamente con la pagina servida. Cerrar esa ventana exigiria pedir
     * REPEATABLE READ, que es otra decision.
     */
    @Transactional
    public PageResponse<ServiceSummaryResponse> listServices(ListServicesQuery query) {
        boolean includePrices = servicePriceVisibility.includePrices();

        long totalElements = serviceRepository.countSearch(query);
        // Una pagina que empieza mas alla del total no tiene nada que traer, y pedirla igual
        // haria que la base ordene todas las filas que pasan el filtro para devolver ninguna.
        boolean beyondLastPage = (long) query.page() * query.size() >= totalElements;
        List<ServiceSummaryResponse> content = beyondLastPage
            ? List.of()
            : serviceRepository.searchPaged(query).stream()
                .map(row -> serviceServiceMapper.toServiceSummaryResponse(row, includePrices))
                .toList();

        return PageResponse.of(content, query.page(), query.size(), totalElements);
    }
}
