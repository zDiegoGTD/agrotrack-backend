package cl.agrotrack.deliveries.dominio;

/**
 * Por que se rechazo una transicion. Determina el codigo HTTP que
 * devuelve el endpoint PUT /api/deliveries/{id}/status.
 */
public enum MotivoRechazo {

    /** El camino entre los dos estados no existe. Responde 409 Conflict. */
    TRANSICION_NO_VALIDA,

    /** La entrega ya esta cerrada: DESPACHADA o RECHAZADA. Responde 409. */
    ESTADO_TERMINAL,

    /** El rol no puede ejecutar cambios de estado. Responde 403 Forbidden. */
    ROL_SIN_PERMISO
}
