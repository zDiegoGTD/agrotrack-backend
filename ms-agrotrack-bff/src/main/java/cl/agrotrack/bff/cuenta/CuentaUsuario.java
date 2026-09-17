package cl.agrotrack.bff.cuenta;

import java.time.Instant;

/** Lo que el BFF necesita saber de la cuenta. users devuelve mas campos; se ignoran. */
public record CuentaUsuario(Long id, String estado, String motivo, Instant primerIngreso) {

    public boolean activa() {
        return "ACTIVO".equals(estado);
    }
}
