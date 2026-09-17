package cl.agrotrack.bff.cuenta;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado de cada cuenta con 60 s de vigencia. Sin esto habria una llamada
 * extra a users por cada request; con esto, desactivar a alguien surte
 * efecto en menos de un minuto.
 */
@Component
public class EstadoCuentas {

    static final Duration VIGENCIA = Duration.ofSeconds(60);

    private record Entrada(CuentaUsuario cuenta, Instant vence) {
    }

    private final Map<String, Entrada> cache = new ConcurrentHashMap<>();
    private final ClienteUsuarios cliente;
    private final Clock clock;

    public EstadoCuentas(ClienteUsuarios cliente, Clock clock) {
        this.cliente = cliente;
        this.clock = clock;
    }

    /** Para el filtro: responde desde la cache si esta vigente. */
    public CuentaUsuario consultar(String oid, String bearer) {
        Entrada e = cache.get(oid);
        if (e != null && clock.instant().isBefore(e.vence())) {
            return e.cuenta();
        }
        return sincronizar(oid, bearer);
    }

    /** Para /api/me: siempre pregunta. Si users falla, la excepcion sube y la entrada vieja no se usa. */
    public CuentaUsuario sincronizar(String oid, String bearer) {
        CuentaUsuario cuenta = cliente.sincronizar(bearer);
        cache.put(oid, new Entrada(cuenta, clock.instant().plus(VIGENCIA)));
        return cuenta;
    }
}
