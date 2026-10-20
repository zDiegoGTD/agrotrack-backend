package cl.agrotrack.mqadmin.infraestructura.web.dto;

import cl.agrotrack.mqadmin.aplicacion.RabbitAdminService.TipoExchange;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuerpos de la API de administracion. La validacion vive aqui y no en el
 * servicio: un nombre vacio o con espacios se rechaza con 400 antes de que
 * la peticion llegue a RabbitMQ.
 *
 * <p>Regla de nombres: letras, numeros, punto, guion y guion bajo, hasta 200
 * caracteres. RabbitMQ admite mas (hasta 255 bytes UTF-8), pero el prefijo
 * {@code amq.} esta reservado por el broker y espacios o comodines solo traen
 * problemas al enrutar; se cortan de raiz.
 */
public final class AdminDtos {

    /** Nombre valido de cola o exchange. */
    public static final String NOMBRE = "^(?!amq\\.)[A-Za-z0-9._-]+$";
    public static final String NOMBRE_MSG =
            "solo letras, numeros, punto, guion y guion bajo; no puede empezar con 'amq.'";

    /** Routing key: ademas admite los comodines * y # de los exchanges topic. */
    public static final String ROUTING_KEY = "^[A-Za-z0-9._*#-]+$";
    public static final String ROUTING_KEY_MSG = "solo letras, numeros, punto, guion, guion bajo, * y #";

    private AdminDtos() {
    }

    public record CrearColaRequest(
            @NotBlank(message = "el nombre de la cola es obligatorio")
            @Size(max = 200, message = "maximo 200 caracteres")
            @Pattern(regexp = NOMBRE, message = NOMBRE_MSG)
            String nombre,
            Boolean durable,
            Boolean conDlq) {

        /** Por defecto durable y con DLQ: el patron del sistema. */
        public boolean esDurable() {
            return durable == null || durable;
        }

        public boolean llevaDlq() {
            return conDlq == null || conDlq;
        }
    }

    public record CrearExchangeRequest(
            @NotBlank(message = "el nombre del exchange es obligatorio")
            @Size(max = 200, message = "maximo 200 caracteres")
            @Pattern(regexp = NOMBRE, message = NOMBRE_MSG)
            String nombre,
            @NotNull(message = "el tipo es obligatorio: DIRECT, TOPIC o FANOUT")
            TipoExchange tipo,
            Boolean durable) {

        public boolean esDurable() {
            return durable == null || durable;
        }
    }

    public record BindingRequest(
            @NotBlank(message = "la cola es obligatoria")
            @Size(max = 200, message = "maximo 200 caracteres")
            @Pattern(regexp = NOMBRE, message = NOMBRE_MSG)
            String cola,
            @NotBlank(message = "el exchange es obligatorio")
            @Size(max = 200, message = "maximo 200 caracteres")
            @Pattern(regexp = NOMBRE, message = NOMBRE_MSG)
            String exchange,
            @NotBlank(message = "la routing key es obligatoria")
            @Size(max = 200, message = "maximo 200 caracteres")
            @Pattern(regexp = ROUTING_KEY, message = ROUTING_KEY_MSG)
            String routingKey) {
    }
}
