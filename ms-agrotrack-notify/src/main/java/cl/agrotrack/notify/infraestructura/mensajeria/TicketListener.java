package cl.agrotrack.notify.infraestructura.mensajeria;

import cl.agrotrack.notify.aplicacion.TicketService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Dominio <b>recepcion en bodega</b>: ticket de pesaje cuando un lote entra.
 * El nombre de la cola viene de {@code agrotrack.rabbit.colas.receipt}.
 */
@Component
public class TicketListener {

    private final ProcesadorComandos procesador;
    private final TicketService tickets;

    public TicketListener(ProcesadorComandos procesador, TicketService tickets) {
        this.procesador = procesador;
        this.tickets = tickets;
    }

    @RabbitListener(queues = "${agrotrack.rabbit.colas.receipt}")
    public void alRecibir(Message m, Channel canal, @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        procesador.procesar(m, canal, tag, tickets::emitir);
    }
}
