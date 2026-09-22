package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.ResourceStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class ResourceStatusRepository implements PanacheRepositoryBase<ResourceStatus, Integer> {

    /**
     * La fila del catalogo cuyo nombre es el dado, SIN distinguir mayusculas, y la de menor id
     * si hubiera mas de una.
     *
     * <p>El puente con la API es el NOMBRE y no el id, que difiere entre ambientes. Lo que
     * tampoco es uniforme es la CAJA, y esto esta MEDIDO en los dos lados: produccion tiene
     * exactamente tres filas, en minuscula y con descripcion; la base de desarrollo tiene
     * seis, las tres en MAYUSCULA con descripcion y los ids mas bajos, y tres en minuscula sin
     * descripcion, con la misma forma exacta que inserta el fixture de pruebas cuando busca un
     * nombre y no lo encuentra. Ninguna migracion ni el sembrador crean ninguna de las seis.
     *
     * <p>Comparar sin distinguir caja es lo unico que resuelve en los dos ambientes: buscar el
     * nombre en minuscula no encuentra nada en desarrollo, y buscarlo en mayuscula no
     * encuentra nada en produccion. Quedarse con el menor id hace la eleccion determinista
     * cuando conviven las dos formas, en vez de depender del orden en que la base las
     * devuelva.
     *
     * <p>Las lecturas no notan la diferencia porque traducen el nombre a mayusculas antes de
     * comparar; la escritura si, porque tiene que elegir UNA fila.
     *
     * <p>NO filtra por vigente, a diferencia de los otros dos catalogos que el alta consulta
     * (el de cargos y el de tipos de documento, que si lo hacen a tres lineas de distancia).
     * La diferencia es deliberada y es de los datos, no del codigo: a esos dos se los retira
     * poniendolos inactivos, y por eso asignar uno retirado es un error de negocio con su
     * codigo. Este no tiene circuito de retiro: sus filas son el dominio cerrado del enum, no
     * hay ninguna inactiva (medido) y apagar una no significaria "ya no se asigna" sino que el
     * dominio quedo sin representante, que es una base rota y no un cuerpo invalido. Filtrar
     * aca convertiria eso en un alta rechazada sin motivo visible.
     */
    public Optional<ResourceStatus> findByNameIgnoringCase(String name) {
        return find("lower(name) = ?1 order by id", name.toLowerCase(java.util.Locale.ROOT))
            .firstResultOptional();
    }
}
