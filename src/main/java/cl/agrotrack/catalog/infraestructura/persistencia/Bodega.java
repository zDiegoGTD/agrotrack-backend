package cl.agrotrack.catalog.infraestructura.persistencia;

import cl.agrotrack.catalog.dominio.CapacidadInsuficienteException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "BODEGA")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bodega {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_bodega")
    @SequenceGenerator(name = "seq_bodega", sequenceName = "SEQ_BODEGA", allocationSize = 1)
    private Long id;

    @Column(nullable = false, length = 120)
    private String nombre;

    @Column(length = 200)
    private String ubicacion;

    @Column(name = "CAPACIDAD_TOTAL", nullable = false, precision = 12, scale = 2)
    private BigDecimal capacidadTotal;

    @Column(name = "CAPACIDAD_DISPONIBLE", nullable = false, precision = 12, scale = 2)
    private BigDecimal capacidadDisponible;

    /**
     * Bloqueo optimista. Dos jefes de acopio recibiendo a la vez en la misma
     * bodega es el caso normal: sin esto, una de las dos restas se pierde.
     */
    @Version
    @Setter(AccessLevel.NONE)
    private Long version;

    public Bodega(String nombre, String ubicacion, BigDecimal capacidadTotal) {
        this.nombre = nombre;
        this.ubicacion = ubicacion;
        this.capacidadTotal = capacidadTotal;
        this.capacidadDisponible = capacidadTotal;
    }

    /** Descuenta espacio al RECIBIR un lote (T2). */
    public void reservar(BigDecimal cantidad) {
        exigirPositiva(cantidad);
        if (capacidadDisponible.compareTo(cantidad) < 0) {
            throw new CapacidadInsuficienteException(capacidadDisponible, cantidad);
        }
        capacidadDisponible = capacidadDisponible.subtract(cantidad);
    }

    /** Devuelve espacio al RECHAZAR un lote ya recibido (T7, T8). Nunca supera el total. */
    public void liberar(BigDecimal cantidad) {
        exigirPositiva(cantidad);
        capacidadDisponible = capacidadDisponible.add(cantidad).min(capacidadTotal);
    }

    /** Cambiar el total ajusta el disponible en la misma medida, sin dejarlo negativo. */
    public void redimensionar(BigDecimal nuevoTotal) {
        exigirPositiva(nuevoTotal);
        BigDecimal ocupado = capacidadTotal.subtract(capacidadDisponible);
        capacidadTotal = nuevoTotal;
        capacidadDisponible = nuevoTotal.subtract(ocupado).max(BigDecimal.ZERO);
    }

    private static void exigirPositiva(BigDecimal cantidad) {
        if (cantidad == null || cantidad.signum() <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor que cero");
        }
    }
}
