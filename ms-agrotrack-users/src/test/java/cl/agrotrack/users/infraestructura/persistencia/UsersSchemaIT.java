package cl.agrotrack.users.infraestructura.persistencia;

import cl.agrotrack.users.soporte.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsersSchemaIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM perfil_productor");
        jdbc.update("DELETE FROM usuario");
    }

    private long insertarUsuario(String oid) {
        Long id = jdbc.queryForObject("SELECT nextval('seq_usuario')", Long.class);
        jdbc.update("""
                INSERT INTO usuario (id, azure_oid, email, nombre, estado, primer_ingreso, ultimo_ingreso)
                VALUES (?, ?, 'x@y.cl', 'X', 'PENDIENTE', now(), now())""", id, oid);
        return id;
    }

    @Test
    @DisplayName("Flyway crea usuario y perfil_productor")
    void tablas() {
        Integer tablas = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN ('usuario','perfil_productor')", Integer.class);
        assertThat(tablas).isEqualTo(2);
    }

    @Test
    @DisplayName("La base rechaza un estado que no existe")
    void checkEstado() {
        long id = insertarUsuario("oid-1");
        assertThatThrownBy(() -> jdbc.update("UPDATE usuario SET estado = 'BORRADO' WHERE id = ?", id))
                .hasMessageContaining("ck_usuario_estado");
    }

    @Test
    @DisplayName("El oid de Azure es unico")
    void oidUnico() {
        insertarUsuario("oid-dup");
        assertThatThrownBy(() -> insertarUsuario("oid-dup")).hasMessageContaining("uk_usuario_azure_oid");
    }

    @Test
    @DisplayName("Dos fichas no pueden compartir RUT")
    void rutUnico() {
        long a = insertarUsuario("oid-a");
        long b = insertarUsuario("oid-b");
        jdbc.update("INSERT INTO perfil_productor (usuario_id, rut, razon_social) VALUES (?, '12345678-9', 'A')", a);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO perfil_productor (usuario_id, rut, razon_social) VALUES (?, '12345678-9', 'B')", b))
                .hasMessageContaining("uk_perfil_rut");
    }
}
