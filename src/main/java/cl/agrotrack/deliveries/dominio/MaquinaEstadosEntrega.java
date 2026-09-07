package cl.agrotrack.deliveries.dominio;

import java.util.EnumSet;
import java.util.Set;

/**
 * Maquina de estados de la entrega. El corazon del caso.
 *
 * <p>Clase pura: sin Spring, sin JPA, sin red. Recibe (estado actual,
 * estado destino, rol) y responde si la transicion procede y que efectos
 * dispara. Se prueba entera en milisegundos, sin levantar infraestructura.
 * Ver docs/00-decisiones.md (D6).
 *
 * <p>La tabla de transiciones vive en docs/01-maquina-de-estados.md.
 */
public final class MaquinaEstadosEntrega {

    /** Quienes pueden hacer avanzar una entrega. */
    private static final Set<Rol> ROLES_QUE_TRANSICIONAN = EnumSet.of(Rol.ADMIN, Rol.OPERADOR);

    /** Quienes pueden crear una entrega: el productor y el jefe de acopio. */
    private static final Set<Rol> ROLES_QUE_REGISTRAN =
            EnumSet.of(Rol.ADMIN, Rol.OPERADOR, Rol.CLIENTE);

    private MaquinaEstadosEntrega() {
    }

    /**
     * Evalua una transicion.
     *
     * <p>El orden de las comprobaciones importa: primero el permiso, despues
     * el estado. Asi un auditor recibe siempre 403 y nunca 409 — un 409 le
     * confirmaria que la transicion habria sido valida, que es informacion
     * que no le corresponde.
     */
    public static ResultadoTransicion evaluar(EstadoEntrega actual, EstadoEntrega destino, Rol rol) {
        if (!ROLES_QUE_TRANSICIONAN.contains(rol)) {
            return ResultadoTransicion.rechazar(MotivoRechazo.ROL_SIN_PERMISO);
        }
        if (actual.esTerminal()) {
            return ResultadoTransicion.rechazar(MotivoRechazo.ESTADO_TERMINAL);
        }
        return switch (actual) {
            case REGISTRADA -> switch (destino) {
                // T2: el lote entra a bodega, asi que la capacidad baja aqui.
                case RECIBIDA -> ResultadoTransicion.permitir(
                        Efecto.DESCONTAR_CAPACIDAD,
                        Efecto.NOTIFICAR_PRODUCTOR,
                        Efecto.EMITIR_TICKET_BODEGA);
                // T6: se rechaza antes de recibir, no hay capacidad que devolver.
                case RECHAZADA -> ResultadoTransicion.permitir(Efecto.NOTIFICAR_PRODUCTOR);
                default -> noValida();
            };
            case RECIBIDA -> switch (destino) {
                // T3
                case EN_CLASIFICACION -> ResultadoTransicion.permitir(Efecto.NOTIFICAR_PRODUCTOR);
                // T7: el lote ya ocupaba bodega, hay que devolver el espacio.
                case RECHAZADA -> ResultadoTransicion.permitir(
                        Efecto.DEVOLVER_CAPACIDAD,
                        Efecto.NOTIFICAR_PRODUCTOR);
                default -> noValida();
            };
            case EN_CLASIFICACION -> switch (destino) {
                // T4: no dispara nada; el lote sigue en bodega.
                case EN_DESPACHO -> ResultadoTransicion.permitir();
                // T8
                case RECHAZADA -> ResultadoTransicion.permitir(
                        Efecto.DEVOLVER_CAPACIDAD,
                        Efecto.NOTIFICAR_PRODUCTOR);
                default -> noValida();
            };
            case EN_DESPACHO -> switch (destino) {
                // T5: el lote sale. Supuesto 1: la capacidad NO se libera aqui.
                case DESPACHADA -> ResultadoTransicion.permitir(
                        Efecto.GENERAR_GUIA_DESPACHO,
                        Efecto.NOTIFICAR_PRODUCTOR);
                // Supuesto 2: cargando el camion, la unica salida es despachar.
                default -> noValida();
            };
            // Inalcanzable: los terminales se filtran arriba. El compilador
            // exige las ramas porque el switch sobre enum debe ser exhaustivo.
            case DESPACHADA, RECHAZADA ->
                    ResultadoTransicion.rechazar(MotivoRechazo.ESTADO_TERMINAL);
        };
    }

    /** Quien puede registrar una entrega nueva (T1). */
    public static boolean puedeRegistrar(Rol rol) {
        return ROLES_QUE_REGISTRAN.contains(rol);
    }

    private static ResultadoTransicion noValida() {
        return ResultadoTransicion.rechazar(MotivoRechazo.TRANSICION_NO_VALIDA);
    }
}
