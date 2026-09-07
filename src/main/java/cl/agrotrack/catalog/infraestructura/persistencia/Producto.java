package cl.agrotrack.catalog.infraestructura.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.type.NumericBooleanConverter;

import java.math.BigDecimal;

@Entity
@Table(name = "PRODUCTO")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Producto {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_producto")
    @SequenceGenerator(name = "seq_producto", sequenceName = "SEQ_PRODUCTO", allocationSize = 1)
    private Long id;

    @Column(nullable = false, length = 30, unique = true)
    private String codigo;

    @Column(nullable = false, length = 120)
    private String nombre;

    @Column(name = "UNIDAD_MEDIDA", nullable = false, length = 10)
    private String unidadMedida;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal tarifa;

    /**
     * Baja logica. Una entrega vieja tiene que poder mostrar su producto.
     *
     * <p>Se guarda como NUMBER(1) y no como el BOOLEAN nativo de Oracle 23,
     * para que el esquema tambien sirva en Oracle 19/21 (lo habitual en las
     * imagenes XE). El converter hace la traduccion 0/1 explicita.
     */
    @Column(nullable = false)
    @Convert(converter = NumericBooleanConverter.class)
    private boolean activo = true;

    public Producto(String codigo, String nombre, String unidadMedida, BigDecimal tarifa) {
        this.codigo = codigo;
        this.nombre = nombre;
        this.unidadMedida = unidadMedida;
        this.tarifa = tarifa;
    }
}
