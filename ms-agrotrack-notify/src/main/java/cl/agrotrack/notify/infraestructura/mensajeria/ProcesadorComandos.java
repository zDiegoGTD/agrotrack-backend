package cl.agrotrack.notify.infraestructura.mensajeria;

import cl.agrotrack.notify.aplicacion.Envelope;
import cl.agrotrack.notify.aplicacion.MensajeInvalidoException;
import cl.agrotrack.notify.aplicacion.ProcesadosStore;
import cl.agrotrack.notify.config.PropiedadesMensajeria;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * El pipeline comun de los tres consumidores de comandos, con la confirmacion
 * <b>explicita</b> de cada mensaje (acknowledge-mode: manual).
 *
 * <p>Cada mensaje termina en exactamente una de estas rutas:
 *
 * <table>
 *   <tr><th>Situacion</th><th>Decision</th><th>Resultado en RabbitMQ</th></tr>
 *   <tr><td>Se ejecuto bien</td><td>{@code basicAck}</td><td>sale de la cola</td></tr>
 *   <tr><td>eventId ya procesado (reentrega)</td><td>{@code basicAck}</td><td>sale sin repetir efectos</td></tr>
 *   <tr><td>Mensaje invalido: JSON roto, sin eventId/type/subject</td>
 *       <td>{@code basicNack(requeue=false)} de inmediato</td><td>DLQ, sin reintentar: nunca va a funcionar</td></tr>
 *   <tr><td>Fallo transitorio (SMTP caido, disco lleno)</td>
 *       <td>reintento con espera creciente</td><td>sigue en proceso</td></tr>
 *   <tr><td>Reintentos agotados</td><td>{@code basicNack(requeue=false)}</td><td>DLQ, con log y metrica</td></tr>
 *   <tr><td>El servicio se esta apagando a mitad de una espera</td>
 *       <td>{@code basicNack(requeue=true)}</td><td>vuelve a la cola para otra instancia</td></tr>
 * </table>
 *
 * <p>Por que nunca {@code requeue=true} ante un error: un mensaje que falla
 * siempre volveria al frente de la cola y la bloquearia en un bucle infinito
 * (el clasico "poison message"). Los reintentos se hacen aqui, acotados.
 *
 * <p>Si el proceso muere antes de confirmar, el mensaje queda sin ACK y el
 * broker lo reentrega: no se pierde. La deduplicacion por eventId evita que
 * esa reentrega ejecute dos veces.
 */
@Component
public class ProcesadorComandos {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorComandos.class);

    public enum Resultado { PROCESADO, DUPLICADO, INVALIDO_A_DLQ, AGOTADO_A_DLQ, DEVUELTO_A_LA_COLA }

    private final ObjectMapper json;
    private final ProcesadosStore procesados;
    private final PropiedadesMensajeria politica;
    private final Counter aDlq;

    public ProcesadorComandos(ObjectMapper json, ProcesadosStore procesados,
                              PropiedadesMensajeria politica, MeterRegistry registry) {
        this.json = json;
        this.procesados = procesados;
        this.politica = politica;
        this.aDlq = Counter.builder("agrotrack.notify.dlq.total")
                .description("Mensajes enviados a DLQ por ser invalidos o tras agotar reintentos")
                .register(registry);
    }

    public Resultado procesar(Message m, Channel canal, long tag, Consumer<Envelope> accion) throws IOException {
        String cola = m.getMessageProperties().getConsumerQueue();

        // 1. Validar. Un mensaje mal formado no mejora reintentando.
        Envelope cmd;
        try {
            cmd = parsear(m);
        } catch (MensajeInvalidoException e) {
            return aLaDlq(canal, tag, Resultado.INVALIDO_A_DLQ, cola, null, e);
        }

        MDC.put("traceId", cmd.traceId());
        try {
            // 2. Deduplicar: una reentrega del broker no repite el efecto.
            if (!procesados.marcarSiEsNuevo(cmd.eventId())) {
                canal.basicAck(tag, false);
                log.info("[ack] {} {} ya procesado: se confirma sin repetir", cmd.type(), cmd.eventId());
                return Resultado.DUPLICADO;
            }

            // 3. Ejecutar con reintentos acotados.
            for (int intento = 1; ; intento++) {
                try {
                    accion.accept(cmd);
                    canal.basicAck(tag, false);
                    log.info("[ack] {} {} procesado (intento {})", cmd.type(), cmd.eventId(), intento);
                    return Resultado.PROCESADO;
                } catch (MensajeInvalidoException e) {
                    // El servicio descubrio que el contenido es invalido: tampoco se reintenta.
                    procesados.desmarcar(cmd.eventId());
                    return aLaDlq(canal, tag, Resultado.INVALIDO_A_DLQ, cola, cmd, e);
                } catch (RuntimeException e) {
                    if (intento >= politica.maxIntentos()) {
                        // Se desmarca para que reprocesar desde la DLQ ejecute de verdad.
                        procesados.desmarcar(cmd.eventId());
                        return aLaDlq(canal, tag, Resultado.AGOTADO_A_DLQ, cola, cmd, e);
                    }
                    var espera = politica.esperaTras(intento);
                    log.warn("[retry] {} {} fallo en el intento {}/{}: {}. Reintento en {} ms",
                            cmd.type(), cmd.eventId(), intento, politica.maxIntentos(), e.getMessage(), espera.toMillis());
                    try {
                        Thread.sleep(espera.toMillis());
                    } catch (InterruptedException apagando) {
                        Thread.currentThread().interrupt();
                        procesados.desmarcar(cmd.eventId());
                        // No es culpa del mensaje: vuelve a la cola para que lo tome otra instancia.
                        canal.basicNack(tag, false, true);
                        log.warn("[nack] {} {} devuelto a la cola: el servicio se esta apagando", cmd.type(), cmd.eventId());
                        return Resultado.DEVUELTO_A_LA_COLA;
                    }
                }
            }
        } finally {
            MDC.remove("traceId");
        }
    }

    /** NACK sin requeue: el broker lo enruta por el x-dead-letter-exchange de la cola a su DLQ. */
    private Resultado aLaDlq(Channel canal, long tag, Resultado motivo, String cola, Envelope cmd, Exception causa)
            throws IOException {
        canal.basicNack(tag, false, false);
        aDlq.increment();
        log.error("[dlq] {} de {} -> DLQ ({}). Causa: {}",
                cmd != null ? cmd.eventId() : "(sin eventId)", cola, motivo, causa.getMessage());
        return motivo;
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
