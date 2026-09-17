package cl.agrotrack.bff.cuenta;

/** Interfaz para probar la cache y el filtro sin red. */
public interface ClienteUsuarios {

    /** Registra o actualiza al dueno del token en ms-agrotrack-users y devuelve su cuenta. */
    CuentaUsuario sincronizar(String bearer);
}
