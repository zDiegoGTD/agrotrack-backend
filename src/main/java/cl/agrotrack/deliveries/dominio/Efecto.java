package cl.agrotrack.deliveries.dominio;

/**
 * Consecuencias de una transicion, expresadas como intenciones.
 *
 * <p>El dominio decide QUE hay que hacer; no sabe COMO ni con que
 * tecnologia. Quien traduce cada efecto a una llamada a catalog, a un
 * mensaje en RabbitMQ o a un evento en Kafka es la capa de aplicacion.
 * Por eso esta clase no importa nada de Spring.
 */
public enum Efecto {

    /** Al recibir el lote baja la capacidad disponible de la bodega. */
    DESCONTAR_CAPACIDAD,

    /** Al rechazar un lote ya recibido, la capacidad vuelve. */
    DEVOLVER_CAPACIDAD,

    /** Email o push al productor. Termina en q.cmd.email. */
    NOTIFICAR_PRODUCTOR,

    /** Ticket de recepcion y pesaje para bodega. Termina en q.cmd.receipt. */
    EMITIR_TICKET_BODEGA,

    /** Guia de despacho en PDF. Termina en q.cmd.voucher. */
    GENERAR_GUIA_DESPACHO
}
