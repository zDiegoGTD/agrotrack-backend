package cl.agrotrack.kafkaadmin.infraestructura.web.dto;

import cl.agrotrack.kafkaadmin.aplicacion.KafkaAdminService.Posicion;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Cuerpos de la API de administracion de Kafka, validados antes de llegar al broker. */
public final class AdminDtos {

    /** Nombres admitidos por Kafka; los que empiezan con "__" son internos (__consumer_offsets). */
    public static final String NOMBRE = "^(?!__)[A-Za-z0-9._-]+$";
    /** Claves de configuracion de topico: minusculas y puntos (retention.ms, cleanup.policy). */
    public static final String CLAVE_CONFIG = "^[a-z][a-z.]*$";

    private AdminDtos() {
    }

    public record TopicoNuevo(
            @NotBlank @Size(max = 200) @Pattern(regexp = NOMBRE, message = "solo letras, numeros, '.', '_' y '-', sin '__' al inicio")
            String nombre,
            @Min(1) @Max(50) int particiones,
            // Hasta 10: si el cluster tiene menos brokers, el broker lo rechaza con su motivo (409)
            @Min(1) @Max(10) short replicas,
            Map<@Pattern(regexp = CLAVE_CONFIG) String, @NotBlank String> configuracion) {
    }

    public record CambioConfiguracion(
            @NotEmpty Map<@Pattern(regexp = CLAVE_CONFIG) String, String> configuracion) {
    }

    public record CambioParticiones(@Min(1) @Max(50) int particiones) {
    }

    public record ReinicioOffsets(@NotNull Posicion posicion) {
    }
}
