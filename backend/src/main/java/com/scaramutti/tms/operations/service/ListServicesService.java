package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.operations.dto.ServiceSummaryResponse;
import com.scaramutti.tms.operations.mapper.ServiceServiceMapper;
import com.scaramutti.tms.operations.service.cmd.ListServicesQuery;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.repository.ServiceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Listado de servicios de transporte. Solo lectura: el total y la pagina comparten el mismo
 * filtrado (y la pagina ni se consulta cuando empieza mas alla del total).
 *
 * <p>Los precios NO son visibles para todos, y esa garantia vive ACA, en el servidor: el
 * despacho opera los viajes pero no ve lo que se cobra por ellos, asi que esconder la columna en
 * la interfaz no alcanzaria (bastaria mirar la respuesta cruda).
 */
@ApplicationScoped
public class ListServicesService {

    /**
     * Roles que SI ven precios. Es una lista positiva a proposito: un rol nuevo que nadie agregue
     * acá no hereda el permiso por accidente, que es el modo seguro de equivocarse.
     */
    private static final Set<String> PRICE_ROLES =
        Set.of("admin", "sales", "general_manager", "operations_manager");

    /**
     * Roles con VETO: no ven precios pase lo que pase. La regla del negocio dice que el despacho
     * nunca los ve, y eso es un veto, no una suma de permisos: si algun dia un usuario tuviera
     * dos roles, "tiene alguno que ve precios" le daria acceso y la regla dice lo contrario.
     */
    private static final Set<String> PRICE_BLIND_ROLES = Set.of("dispatcher");

    @Inject ServiceRepository serviceRepository;
    @Inject ServiceServiceMapper serviceServiceMapper;
    @Inject CurrentUser currentUser;

    /**
     * Transaccional aunque solo lea: el total y la pagina son dos consultas y asi comparten una
     * sola conexion, en vez de tomar y devolver dos.
     *
     * <p>OJO con lo que esto NO garantiza: con el aislamiento por defecto (READ COMMITTED) cada
     * sentencia toma su propio snapshot, asi que un alta concurrente todavia puede hacer que el
     * total no calce exactamente con la pagina servida. Cerrar esa ventana exigiria pedir
     * REPEATABLE READ, que es otra decision.
     */
    @Transactional
    public PageResponse<ServiceSummaryResponse> listServices(ListServicesQuery query) {
        boolean includePrices = includePrices();

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

    /** El veto manda sobre la lista positiva: primero se pregunta quien NO puede ver precios. */
    private boolean includePrices() {
        return !currentUser.hasAnyRole(PRICE_BLIND_ROLES) && currentUser.hasAnyRole(PRICE_ROLES);
    }
}
