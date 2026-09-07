package cl.agrotrack.deliveries.aplicacion;

import cl.agrotrack.deliveries.dominio.EstadoEntrega;
import cl.agrotrack.deliveries.dominio.MotivoRechazo;

/** Errores de aplicacion; el ApiExceptionHandler los traduce a HTTP. */
public final class Excepciones {

    private Excepciones() {
    }

    /** 404 */
    public static class RecursoNoEncontrado extends RuntimeException {
        public RecursoNoEncontrado(String recurso, Object id) {
            super(recurso + " " + id + " no existe");
        }
    }

    /** 403: el actor no puede ver o tocar este recurso. */
    public static class AccesoDenegado extends RuntimeException {
        public AccesoDenegado(String detalle) {
            super(detalle);
        }
    }

    /** 409 (o 403 si el motivo es el rol): la maquina de estados dijo que no. */
    public static class TransicionNoPermitida extends RuntimeException {
        private final MotivoRechazo motivo;
        private final EstadoEntrega actual;
        private final EstadoEntrega destino;

        public TransicionNoPermitida(MotivoRechazo motivo, EstadoEntrega actual, EstadoEntrega destino) {
            super(mensaje(motivo, actual, destino));
            this.motivo = motivo;
            this.actual = actual;
            this.destino = destino;
        }

        public MotivoRechazo motivo() {
            return motivo;
        }

        public EstadoEntrega actual() {
            return actual;
        }

        public EstadoEntrega destino() {
            return destino;
        }

        private static String mensaje(MotivoRechazo motivo, EstadoEntrega actual, EstadoEntrega destino) {
            return switch (motivo) {
                case ROL_SIN_PERMISO -> "Su rol no puede cambiar el estado de una entrega";
                case ESTADO_TERMINAL -> "La entrega ya esta " + actual.etiqueta().toLowerCase() + " y no admite cambios";
                case TRANSICION_NO_VALIDA -> "No se puede pasar de " + actual + " a " + destino;
            };
        }
    }

    /** 409: catalog dijo que la bodega no tiene espacio. */
    public static class CapacidadInsuficiente extends RuntimeException {
        public CapacidadInsuficiente(String detalle) {
            super(detalle);
        }
    }

    /** 503: catalog no responde; la transicion no se puede completar. */
    public static class CatalogNoDisponible extends RuntimeException {
        public CatalogNoDisponible(Throwable causa) {
            super("El servicio de catalogo no esta disponible", causa);
        }
    }
}
