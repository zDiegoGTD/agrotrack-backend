package cl.agrotrack.notify.infraestructura.mensajeria;

import cl.agrotrack.notify.aplicacion.EmailService;
import cl.agrotrack.notify.aplicacion.Envelope;
import cl.agrotrack.notify.aplicacion.MensajeInvalidoException;
import cl.agrotrack.notify.aplicacion.ProcesadosStore;
import cl.agrotrack.notify.aplicacion.TicketService;
import cl.agrotrack.notify.aplicacion.VoucherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Consumidor de las tres colas de comandos. Un metodo por cola, el mismo
 * pipeline para todos: parsear -> deduplicar -> ejecutar.
 *
 * <p><b>ACK/NACK:</b> el contenedor (modo AUTO de Spring, que no es el
 * auto-ack del broker) confirma el mensaje solo cuando este metodo retorna
 * sin excepcion, y lo rechaza sin requeue cuando lanza. Terminar normalmente
 * es el ACK; lanzar es el NACK. Un proceso que muere a mitad de camino deja
 * el mensaje sin confirmar y el broker lo reentrega: no se pierde.
 *
 * <p>Los reintentos (3, con backoff) y el paso a la DLQ los aplica
 * RabbitConfig alrededor de esta llamada.
 */
@Component
public class ComandosListener {

    private static final Logger log = LoggerFactory.getLogger(ComandosListener.class);

    private final ObjectMapper json;
    private final ProcesadosStore procesados;
    private final EmailService emails;
    private final TicketService tickets;
    private final VoucherService vouchers;

    public ComandosListener(ObjectMapper json, ProcesadosStore procesados,
                            EmailService emails, TicketService tickets, VoucherService vouchers) {
        this.json = json;
        this.procesados = procesados;
        this.emails = emails;
        this.tickets = tickets;
        this.vouchers = vouchers;
    }

    @RabbitListener(queues = "q.cmd.email")
    public void email(Message m) {
        procesar(m, emails::enviar);
    }

    @RabbitListener(queues = "q.cmd.receipt")
    public void receipt(Message m) {
        procesar(m, tickets::emitir);
    }

    @RabbitListener(queues = "q.cmd.voucher")
    public void voucher(Message m) {
        procesar(m, vouchers::generar);
    }

    private void procesar(Message m, Consumer<Envelope> accion) {
        Envelope cmd = parsear(m);
        MDC.put("traceId", cmd.traceId());
        try {
            if (!procesados.marcarSiEsNuevo(cmd.eventId())) {
                log.info("[dup] {} {} ya procesado, se descarta", cmd.type(), cmd.eventId());
                return; // retorno normal = ACK: el duplicado se consume sin efecto
            }
            accion.accept(cmd);
        } catch (RuntimeException e) {
            // Si fallo, el eventId no debe quedar marcado: el reintento tiene que ejecutar de verdad.
            procesados.desmarcar(cmd.eventId());
            throw e;
        } finally {
            MDC.remove("traceId");
        }
    }

    private Envelope parsear(Message m) {
        String cuerpo = new String(m.getBody(), StandardCharsets.UTF_8);
        Envelope env;
        try {
            env = json.readValue(cuerpo, Envelope.class);
        } catch (IOException e) {
            throw new MensajeInvalidoException("JSON invalido: " + resumen(cuerpo), e);
        }
        if (env.eventId() == null || env.eventId().isBlank()) {
            throw new MensajeInvalidoException("Mensaje sin eventId: " + resumen(cuerpo));
        }
        if (env.type() == null || env.subject() == null) {
            throw new MensajeInvalidoException("Mensaje sin type o subject: " + resumen(cuerpo));
        }
        return env;
    }

    private static String resumen(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
