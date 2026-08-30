package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.model.ServiceResourceKind;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

/**
 * Las claves con las que se lockean los recursos de un viaje, y el ORDEN en el que se toman.
 *
 * <p>Es una clase aparte y sin estado por una sola razon: el orden es una garantia que se puede
 * romper sin que nada falle a la vista, y aca se puede afirmar directamente en un test, sin base
 * de datos ni transacciones de por medio.
 */
public final class ServiceResourceLockKeys {

    /**
     * Espacio de nombres de estas claves. Va adentro de la clave para que no choque con los
     * advisory locks de otros modulos: PostgreSQL no separa por quien los toma, solo por el
     * numero.
     */
    private static final String NAMESPACE = "operaciones.service_resource:";

    private ServiceResourceLockKeys() {}

    /**
     * Las claves de los recursos presentes, SIEMPRE en el mismo orden.
     *
     * <p>El orden es lo que impide el abrazo mortal: dos asignaciones que compartan dos recursos
     * los van a pedir en la misma secuencia, asi que la segunda espera a la primera en el primer
     * recurso comun y nunca al reves. Ordenar por el texto de la clave —y no por el id— alcanza
     * porque el tipo va adelante: todos los conductores antes que todos los tractos.
     *
     * <p>La carreta es opcional; cuando no viene, simplemente no hay clave que tomar.
     */
    public static List<String> ordered(Integer driverId, Integer tractorId, Integer trailerId) {
        List<String> keys = new ArrayList<>();
        addKey(keys, ServiceResourceKind.DRIVER, driverId);
        addKey(keys, ServiceResourceKind.TRACTOR, tractorId);
        addKey(keys, ServiceResourceKind.TRAILER, trailerId);
        keys.sort(String::compareTo);
        return List.copyOf(keys);
    }

    /**
     * La clave de UN recurso. Lleva el tipo adentro: sin el, el conductor 7 y el tracto 7
     * compartirian lock y se serializarian dos asignaciones que no tienen nada en comun.
     *
     * <p>El formato va anclado a un locale fijo: {@code String.format} usa el del sistema, y con
     * uno cuyo sistema de numeracion no sea el arabigo occidental los digitos saldrian distintos,
     * o sea que el MISMO recurso mapearia a dos locks distintos en dos replicas y la
     * serializacion desapareceria en silencio.
     *
     * <p>El id se escribe RELLENADO a diez cifras para que el orden por texto coincida con el
     * orden numerico. Sin eso el recurso 9 iria despues del 10, que igual es un orden estable
     * —y para evitar el abrazo mortal con eso alcanza—, pero uno que no se puede explicar al
     * leerlo, y una garantia que no se puede explicar es una que nadie mantiene.
     */
    public static String of(ServiceResourceKind kind, int resourceId) {
        return NAMESPACE + kind.name() + ":" + String.format(Locale.ROOT, "%010d", resourceId);
    }

    private static void addKey(List<String> keys, ServiceResourceKind kind, Integer resourceId) {
        if (resourceId != null) {
            keys.add(of(kind, resourceId));
        }
    }
}
