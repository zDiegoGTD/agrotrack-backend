package cl.agrotrack.deliveries.infraestructura.mensajeria;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;

/**
 * Lado productor de los mensajes no entregados: lo que RabbitMQ no pudo
 * enrutar o se nego a recibir queda en el log con el cuerpo completo, para
 * poder reinyectarlo, y se cuenta en {@code agrotrack.deliveries.comandos.no_entregados}.
 *
 * <p>Dos avisos distintos del broker:
 * <ul>
 *   <li><b>Return</b> ({@code mandatory=true}): el exchange existe pero ninguna
 *       cola tiene un binding para esa routing key. Sin {@code mandatory}, RabbitMQ
 *       descartaria el comando en silencio.</li>
 *   <li><b>Confirm con NACK</b> (publisher confirms): el broker no pudo hacerse
 *       cargo del mensaje (por ejemplo, el exchange no existe o un error interno).</li>
 * </ul>
 *
 * <p>Del lado consumidor, los mensajes que fallan van a la DLQ (notify); esto
 * cubre los que nunca llegaron a una cola.
 */
public class RegistroNoEntregados implements RabbitTemplate.ReturnsCallback, RabbitTemplate.ConfirmCallback {

    private static final Logger log = LoggerFactory.getLogger(RegistroNoEntregados.class);

    private final Counter noEntregados;

    public RegistroNoEntregados(MeterRegistry registry) {
        this.noEntregados = Counter.builder("agrotrack.deliveries.comandos.no_entregados")
                .description("Comandos que RabbitMQ no pudo enrutar o rechazo al publicarlos")
                .register(registry);
    }

    /** Conecta los dos avisos al template: lo usa MensajeriaConfig. */
    public void registrarEn(RabbitTemplate template) {
        template.setMandatory(true);
        template.setReturnsCallback(this);
        template.setConfirmCallback(this);
    }

    @Override
    public void returnedMessage(ReturnedMessage devuelto) {
        noEntregados.increment();
        log.error("[rabbit] NO ENRUTADO eventId={} exchange={} routingKey={} ({} {}) -> reinyectar: {}",
                devuelto.getMessage().getMessageProperties().getMessageId(),
                devuelto.getExchange(), devuelto.getRoutingKey(),
                devuelto.getReplyCode(), devuelto.getReplyText(),
                new String(devuelto.getMessage().getBody(), StandardCharsets.UTF_8));
    }

    @Override
    public void confirm(CorrelationData correlacion, boolean ack, String causa) {
        if (ack) {
            return;
        }
        noEntregados.increment();
        String eventId = correlacion != null ? correlacion.getId() : "(sin correlacion)";
        String cuerpo = correlacion instanceof ComandoPublicado c ? c.cuerpo() : "";
        log.error("[rabbit] RECHAZADO POR EL BROKER eventId={} causa={} -> reinyectar: {}", eventId, causa, cuerpo);
    }

    /** CorrelationData que ademas recuerda el cuerpo, para poder loguearlo si el broker hace NACK. */
    public static class ComandoPublicado extends CorrelationData {
        private final String cuerpo;

        public ComandoPublicado(String eventId, String cuerpo) {
            super(eventId);
            this.cuerpo = cuerpo;
        }

        public String cuerpo() {
            return cuerpo;
        }
    }
}
