package cl.agrotrack.deliveries.dominio;

/**
 * Quien ejecuta una accion: identidad del JWT y el rol con el que actua.
 *
 * <p>Si el token trae varios roles se toma el mas privilegiado para
 * evaluar la maquina de estados; {@code roles} conserva todos para el
 * envelope de auditoria.
 */
public record Actor(String userId, String nombre, Rol rol) {

    public static final Actor SISTEMA = new Actor("sistema", "Sistema", Rol.ADMIN);
}
