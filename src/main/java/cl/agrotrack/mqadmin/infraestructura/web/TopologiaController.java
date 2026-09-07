package cl.agrotrack.mqadmin.infraestructura.web;

import cl.agrotrack.mqadmin.config.TopologiaRabbit;
import cl.agrotrack.mqadmin.config.TopologiaRabbit.Flujo;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Estado de la topologia: lo declarado y lo que el broker reporta ahora
 * (mensajes encolados, consumidores). La tasa de mensajes en DLQ que pide
 * el enunciado sale de aqui: {@code enDlq} mayor que cero es una alarma.
 */
@RestController
@RequestMapping("/api/mq")
public class TopologiaController {

    private final AmqpAdmin admin;

    public TopologiaController(AmqpAdmin admin) {
        this.admin = admin;
    }

    public record EstadoCola(String nombre, Integer mensajes, Integer consumidores, boolean existe) {
        static EstadoCola de(String nombre, QueueInformation info) {
            return info == null
                    ? new EstadoCola(nombre, null, null, false)
                    : new EstadoCola(nombre, info.getMessageCount(), info.getConsumerCount(), true);
        }
    }

    public record EstadoFlujo(String proposito, String routingKeyDirect, String routingKeyTopic,
                              EstadoCola cola, EstadoCola dlq) {
    }

    public record Topologia(List<String> exchanges, List<EstadoFlujo> flujos, long totalEnDlq) {
    }

    @GetMapping("/topology")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Topologia topologia() {
        List<EstadoFlujo> flujos = TopologiaRabbit.FLUJOS.stream().map(this::estado).toList();
        long enDlq = flujos.stream()
                .map(f -> f.dlq().mensajes())
                .filter(m -> m != null)
                .mapToLong(Integer::longValue)
                .sum();
        return new Topologia(
                List.of(TopologiaRabbit.EXCHANGE_DIRECT, TopologiaRabbit.EXCHANGE_TOPIC, TopologiaRabbit.EXCHANGE_DLX),
                flujos, enDlq);
    }

    private EstadoFlujo estado(Flujo f) {
        return new EstadoFlujo(f.proposito(), f.routingKeyDirect(), f.routingKeyTopic(),
                EstadoCola.de(f.cola(), admin.getQueueInfo(f.cola())),
                EstadoCola.de(f.dlq(), admin.getQueueInfo(f.dlq())));
    }
}
