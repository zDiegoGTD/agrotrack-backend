package cl.agrotrack.users.aplicacion;

import cl.agrotrack.users.dominio.EstadoUsuario;
import cl.agrotrack.users.soporte.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SincronizarIT extends PostgresIT {

    @Autowired UsuarioService servicio;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM perfil_productor");
        jdbc.update("DELETE FROM usuario");
    }

    private static IdentidadToken token(String oid, String... roles) {
        return new IdentidadToken(oid, oid + "@agrotrack.cl", "Nombre " + oid, List.of(roles));
    }

    @Test
    @DisplayName("Sincronizar dos veces deja UN registro y solo mueve ultimo_ingreso")
    void idempotente() {
        var primera = servicio.sincronizar(token("oid-1", "CLIENTE"));
        // Se envejece el ingreso para que el segundo sea medible sin depender del reloj
        jdbc.update("UPDATE usuario SET ultimo_ingreso = ultimo_ingreso - interval '1 hour'");

        var segunda = servicio.sincronizar(token("oid-1", "CLIENTE"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usuario", Integer.class)).isEqualTo(1);
        assertThat(segunda.id()).isEqualTo(primera.id());
        assertThat(segunda.primerIngreso()).isEqualTo(primera.primerIngreso());
        assertThat(segunda.ultimoIngreso()).isAfter(segunda.primerIngreso());
        assertThat(segunda.estado()).isEqualTo(EstadoUsuario.PENDIENTE);
    }

    @Test
    @DisplayName("Actualiza nombre, correo y rol del token en cada ingreso")
    void actualizaDatos() {
        servicio.sincronizar(token("oid-2", "CLIENTE"));
        var r = servicio.sincronizar(new IdentidadToken("oid-2", "nuevo@agrotrack.cl", "Nombre Nuevo", List.of("OPERADOR")));

        assertThat(r.email()).isEqualTo("nuevo@agrotrack.cl");
        assertThat(r.nombre()).isEqualTo("Nombre Nuevo");
        assertThat(r.rolUltimoToken()).isEqualTo("OPERADOR");
    }

    @Test
    @DisplayName("El primer admin nace ACTIVO aprobado por 'sistema'; el segundo nace PENDIENTE")
    void primerAdminAcotado() {
        var primero = servicio.sincronizar(token("admin-1", "ADMIN"));
        var segundo = servicio.sincronizar(token("admin-2", "ADMIN"));

        assertThat(primero.estado()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(primero.aprobadoPor()).isEqualTo("sistema");
        assertThat(primero.aprobadoEn()).isNotNull();
        assertThat(segundo.estado()).isEqualTo(EstadoUsuario.PENDIENTE);
    }

    @Test
    @DisplayName("Sin ADMIN en el token nunca se auto-aprueba, aunque la tabla este vacia")
    void noAdminNuncaSeAutoAprueba() {
        assertThat(servicio.sincronizar(token("cli-1", "CLIENTE")).estado()).isEqualTo(EstadoUsuario.PENDIENTE);
        assertThat(servicio.sincronizar(token("sin-rol")).estado()).isEqualTo(EstadoUsuario.PENDIENTE);
    }

    @Test
    @DisplayName("Ocho admins entrando a la vez con el sistema vacio: exactamente uno queda ACTIVO")
    void primerAdminConcurrente() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Object>> tareas = IntStream.range(0, 8)
                    .<Callable<Object>>mapToObj(i -> () -> servicio.sincronizar(token("admin-c" + i, "ADMIN")))
                    .toList();
            for (var f : pool.invokeAll(tareas)) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usuario WHERE estado = 'ACTIVO'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usuario", Integer.class)).isEqualTo(8);
    }
}
