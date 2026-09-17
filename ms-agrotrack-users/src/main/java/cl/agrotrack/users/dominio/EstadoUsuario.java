package cl.agrotrack.users.dominio;

/** Ciclo de vida de una cuenta en AgroTrack. Solo ACTIVO puede usar el sistema. */
public enum EstadoUsuario {
    PENDIENTE,
    ACTIVO,
    RECHAZADO,
    INACTIVO
}
