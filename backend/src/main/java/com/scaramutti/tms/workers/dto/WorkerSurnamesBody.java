package com.scaramutti.tms.workers.dto;

/**
 * Los dos cuerpos que traen el apellido, el alta y la edicion. La regla de cual tiene que venir
 * los lee por aca porque un validador es de un solo tipo: atado a uno, el otro daria un 500.
 */
public interface WorkerSurnamesBody {

    String lastName();

    String paternalLastName();

    String maternalLastName();
}
