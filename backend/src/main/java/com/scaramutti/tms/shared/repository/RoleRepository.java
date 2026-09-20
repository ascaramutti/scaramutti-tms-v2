package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.Role_;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.panache.common.Sort;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class RoleRepository implements PanacheRepositoryBase<Role, Integer> {

    /**
     * Los roles vigentes, del nivel 4 al 1 y, a igual nivel, por id: se lee como el
     * organigrama, de arriba abajo.
     *
     * <p>La direccion se declara por columna y no encadenando el sentido al final, que lo
     * aplicaria a las DOS y dejaria el id al reves.
     *
     * <p>Sin filtro por nivel ni por si el rol inicia sesion: el catalogo es el mismo para
     * todos. Filtrar en el servidor obligaria al catalogo a saber quien pregunta, y el
     * detalle de un trabajador igual necesita mostrar cargos que quien mira no puede
     * asignar. La autoridad sobre quien asigna que cargo es la regla de las escrituras.
     */
    public List<Role> listActiveByLevelDesc() {
        return list("isActive",
            Sort.by("level", Sort.Direction.Descending).and("id", Sort.Direction.Ascending),
            true);
    }

    public Optional<Role> findByName(String name) {
        return find(Role_.NAME, name).singleResultOptional();
    }
}
