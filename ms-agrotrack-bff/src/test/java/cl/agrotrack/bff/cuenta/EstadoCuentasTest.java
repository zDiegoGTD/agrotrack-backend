package cl.agrotrack.bff.cuenta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** La cache evita una llamada a users por request, sin servir un estado viejo mas de 60 s. */
class EstadoCuentasTest {

    static class RelojMovible extends Clock {
        Instant ahora = Instant.parse("2026-09-15T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return ahora; }

        void avanzar(Duration d) { ahora = ahora.plus(d); }
    }

    /** users de mentira: devuelve el estado que se le diga y cuenta las llamadas. */
    static class UsersFalso implements ClienteUsuarios {
        final AtomicInteger llamadas = new AtomicInteger();
        String estado = "PENDIENTE";
        boolean caido = false;

        @Override
        public CuentaUsuario sincronizar(String bearer) {
            llamadas.incrementAndGet();
            if (caido) throw new UsuariosNoDisponibleException("caido", null);
            return new CuentaUsuario(1L, estado, null, null);
        }
    }

    RelojMovible reloj;
    UsersFalso users;
    EstadoCuentas cuentas;

    @BeforeEach
    void preparar() {
        reloj = new RelojMovible();
        users = new UsersFalso();
        cuentas = new EstadoCuentas(users, reloj);
    }

    @Test
    @DisplayName("Dentro de 60 s se responde desde la cache: una sola llamada")
    void cacheVigente() {
        cuentas.consultar("oid", "t");
        reloj.avanzar(Duration.ofSeconds(59));
        cuentas.consultar("oid", "t");

        assertThat(users.llamadas).hasValue(1);
    }

    @Test
    @DisplayName("Pasados 60 s se vuelve a preguntar y se ve el estado nuevo")
    void cacheVencida() {
        cuentas.consultar("oid", "t");
        users.estado = "ACTIVO";
        reloj.avanzar(Duration.ofSeconds(61));

        assertThat(cuentas.consultar("oid", "t").estado()).isEqualTo("ACTIVO");
        assertThat(users.llamadas).hasValue(2);
    }

    @Test
    @DisplayName("sincronizar siempre llama y refresca la cache (lo usa /api/me)")
    void sincronizarRefresca() {
        cuentas.consultar("oid", "t");
        users.estado = "ACTIVO";

        cuentas.sincronizar("oid", "t");

        assertThat(cuentas.consultar("oid", "t").estado()).isEqualTo("ACTIVO");
        assertThat(users.llamadas).hasValue(2);
    }

    @Test
    @DisplayName("Si users cae con la cache vencida, se propaga el fallo: nunca se sirve el estado viejo")
    void caidoNoSirveViejo() {
        users.estado = "ACTIVO";
        cuentas.consultar("oid", "t");
        reloj.avanzar(Duration.ofSeconds(61));
        users.caido = true;

        assertThatThrownBy(() -> cuentas.consultar("oid", "t")).isInstanceOf(UsuariosNoDisponibleException.class);
    }

    @Test
    @DisplayName("Cada usuario tiene su propia entrada")
    void porUsuario() {
        cuentas.consultar("a", "t");
        cuentas.consultar("b", "t");

        assertThat(users.llamadas).hasValue(2);
    }
}
