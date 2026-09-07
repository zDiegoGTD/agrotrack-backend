package cl.agrotrack.deliveries.dominio;

/**
 * Roles del caso (seccion 2 del enunciado).
 *
 * <p>Los nombres son los que llegan en el claim {@code roles} del JWT de
 * Azure AD, no los nombres de negocio: el Jefe de acopio es OPERADOR y el
 * Productor es CLIENTE.
 */
public enum Rol {

    /** Administra catalogo y capacidad, y ve los KPIs de la red. */
    ADMIN,

    /** Jefe de acopio: recibe, clasifica y despacha. */
    OPERADOR,

    /** Productor: registra y sigue sus entregas. */
    CLIENTE,

    /** Solo lectura, para trazabilidad. Nunca escribe. */
    AUDITOR
}
