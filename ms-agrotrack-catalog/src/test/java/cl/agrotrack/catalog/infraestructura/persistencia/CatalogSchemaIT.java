package cl.agrotrack.catalog.infraestructura.persistencia;

import cl.agrotrack.catalog.soporte.PostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogSchemaIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductoRepository productos;
    @Autowired BodegaRepository bodegas;

    @Test
    @DisplayName("Flyway aplica V1 y las tablas existen")
    void flywayAplicaMigracion() {
        Integer aplicadas = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(aplicadas).isGreaterThanOrEqualTo(1);

        Integer tablas = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN ('producto','bodega')", Integer.class);
        assertThat(tablas).isEqualTo(2);
    }

    @Test
    @DisplayName("Un producto se guarda y se recupera por codigo")
    void guardaProducto() {
        productos.save(new Producto("TRIGO-01", "Trigo candeal", "KG", new BigDecimal("120.50")));

        assertThat(productos.findByCodigo("TRIGO-01"))
                .isPresent()
                .get()
                .satisfies(p -> {
                    assertThat(p.getId()).isNotNull();
                    assertThat(p.isActivo()).isTrue();
                    assertThat(p.getTarifa()).isEqualByComparingTo("120.50");
                });
    }

    @Test
    @DisplayName("La base rechaza una capacidad disponible negativa aunque el codigo lo intente")
    void checkDeCapacidadEnLaBase() {
        Bodega b = bodegas.saveAndFlush(new Bodega("Bodega Norte", "Curico", new BigDecimal("100")));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE bodega SET capacidad_disponible = -1 WHERE id = ?", b.getId()))
                .hasMessageContaining("ck_bodega_capacidad");
    }
}
