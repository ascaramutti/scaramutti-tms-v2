package com.scaramutti.tms.auth.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.mapper.AuthServiceMapper;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resuelve una FK {@code created_by}/{@code updated_by} a {@link UserResponse}
 * (el DTO canónico de usuario del proyecto). Extraído al aparecer la 4ª copia
 * del mismo {@code loadUser} (3 en quotations: Create/Get/Update, 1 en
 * warehouse: WarehouseProductService), mismo criterio del PR #88 (dedup de un
 * patrón repetido a {@code shared/}). Refactor puro, sin cambio de
 * comportamiento observable.
 *
 * <p>Invariante asumida (igual que antes en cada copia): los usuarios NO se
 * borran en duro — {@code created_by} es FK NOT NULL y la baja es lógica via
 * {@code isActive}. Por eso la resolución nunca debería fallar; si falla es un
 * orphan FK real (dato corrupto) y se trata como error interno con mensaje
 * accionable, NO como un 404 de negocio.
 */
@ApplicationScoped
public class UserLookup {

    private static final Logger LOG = Logger.getLogger(UserLookup.class);

    @Inject UserRepository userRepository;
    @Inject AuthServiceMapper authServiceMapper;

    /**
     * Resuelve un único id a {@link UserResponse}. Orphan FK → INTERNAL_ERROR
     * (500 con mensaje) — no debería ocurrir bajo la invariante de baja lógica.
     */
    public UserResponse require(Integer userId) {
        User user = userRepository.findById(userId);
        if (user == null) {
            LOG.errorf("Orphan FK: user not found, userId=%s — un registro referencia un usuario que ya no existe", userId);
            throw CommonError.INTERNAL_ERROR.toException(
                "El registro referencia un usuario inexistente (id=" + userId + "). Reporte a soporte."
            );
        }
        return authServiceMapper.toUserResponse(user);
    }

    /**
     * Resuelve un lote de ids a {@link UserResponse} en 1 query (dedup por Set),
     * indexados por id. Pensado para el read-path de listados paginados: evita
     * el N+1 de resolver el {@code createdBy} fila por fila.
     *
     * <p>Enforcement (mismo contrato que {@link #require}): si algún id del lote no
     * resuelve es un orphan FK real → {@code INTERNAL_ERROR} listando los faltantes,
     * en vez de omitirlo en silencio del mapa.
     *
     * <p>Nota de performance (igual que antes en ListQuotationsService): las
     * asociaciones EAGER de {@code User} (worker, role) generan SELECTs extra de
     * Hibernate por cada creador distinto K, no por page size — K = tamaño del
     * equipo, mismo costo que el login. {@code batch-fetch-size} global lo
     * colapsaría cross-módulo si hiciera falta.
     */
    public Map<Integer, UserResponse> requireAllById(Collection<Integer> userIds) {
        Set<Integer> distinctIds = Set.copyOf(userIds);
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, UserResponse> byId = userRepository.list("id IN ?1", distinctIds).stream()
            .collect(Collectors.toMap(user -> user.id, authServiceMapper::toUserResponse));

        // Enforcement (mismo contrato que require): si algún id no resolvió es un
        // orphan FK real (restore malo, cirugía manual, cascade futuro) → COM-500
        // ruidoso con los ids faltantes, en vez de dejar createdBy=null en silencio.
        if (byId.size() != distinctIds.size()) {
            Set<Integer> missing = new HashSet<>(distinctIds);
            missing.removeAll(byId.keySet());
            LOG.errorf("Orphan FK (batch): usuarios no encontrados, ids=%s — algún registro referencia usuarios que ya no existen", missing);
            throw CommonError.INTERNAL_ERROR.toException(
                "Uno o más registros referencian usuarios inexistentes (ids=" + missing + "). Reporte a soporte."
            );
        }
        return byId;
    }
}
