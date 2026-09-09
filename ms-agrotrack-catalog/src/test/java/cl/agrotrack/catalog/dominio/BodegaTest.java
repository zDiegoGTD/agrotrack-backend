package cl.agrotrack.catalog.dominio;

import cl.agrotrack.catalog.infraestructura.persistencia.Bodega;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reglas de capacidad, sin base de datos. */
class BodegaTest {

    private Bodega bodega() {
        return new Bodega("Norte", "Curico", new BigDecimal("100"));
    }

    @Test
    @DisplayName("Reservar descuenta del disponible")
    void reservarDescuenta() {
        Bodega b = bodega();
        b.reservar(new BigDecimal("30"));
        assertThat(b.getCapacidadDisponible()).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("Reservar mas de lo disponible falla y no deja la bodega a medias")
    void reservarSinEspacio() {
        Bodega b = bodega();
        b.reservar(new BigDecimal("90"));

        assertThatThrownBy(() -> b.reservar(new BigDecimal("20")))
                .isInstanceOf(CapacidadInsuficienteException.class);
        assertThat(b.getCapacidadDisponible()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("Liberar nunca supera la capacidad total")
    void liberarNoSuperaTotal() {
        Bodega b = bodega();
        b.reservar(new BigDecimal("10"));
        b.liberar(new BigDecimal("50"));
        assertThat(b.getCapacidadDisponible()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("Redimensionar conserva lo ocupado")
    void redimensionarConservaOcupado() {
        Bodega b = bodega();
        b.reservar(new BigDecimal("40"));      // ocupado 40
        b.redimensionar(new BigDecimal("60")); // disponible = 60 - 40
        assertThat(b.getCapacidadDisponible()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("Cantidades cero o negativas se rechazan")
    void cantidadesInvalidas() {
        Bodega b = bodega();
        assertThatThrownBy(() -> b.reservar(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> b.liberar(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
    }
}
