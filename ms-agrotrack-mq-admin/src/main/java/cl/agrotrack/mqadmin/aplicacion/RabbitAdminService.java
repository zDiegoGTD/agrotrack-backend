package cl.agrotrack.mqadmin.aplicacion;

import cl.agrotrack.mqadmin.aplicacion.AdministracionInvalidaException.Motivo;
import cl.agrotrack.mqadmin.config.TopologiaRabbit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Toda la administracion de la topologia de RabbitMQ en un solo lugar.
 *
 * <p>El controlador solo traduce HTTP: no conoce {@link AmqpAdmin} ni los
 * tipos de Spring AMQP. Aqui viven las reglas que no son de transporte:
 *
 * <ul>
 *   <li>Lo que declara {@link TopologiaRabbit} es del sistema y no se borra
 *       por API: si alguien elimina {@code q.cmd.email} en caliente, los
 *       comandos de notify se pierden sin que nadie se entere.</li>
 *   <li>Declarar es idempotente mientras los atributos calcen; si no calzan,
 *       el broker cierra el canal y eso se traduce a 409 en vez de 500.</li>
 *   <li>Una cola creada por API nace con su DLQ si se pide, para que el
 *       patron del sistema (cola + cola.dlq) se mantenga.</li>
 * </ul>
 */
@Service
public class RabbitAdminService {

    private static final Logger log = LoggerFactory.getLogger(RabbitAdminService.class);

    private final AmqpAdmin admin;

    public RabbitAdminService(AmqpAdmin admin) {
        this.admin = admin;
    }

    /** Tipos de exchange que la API acepta crear. */
    public enum TipoExchange { DIRECT, TOPIC, FANOUT }

    public record EstadoCola(String nombre, Integer mensajes, Integer consumidores, boolean existe) {
        static EstadoCola de(String nombre, QueueInformation info) {
            return info == null
                    ? new EstadoCola(nombre, null, null, false)
                    : new EstadoCola(nombre, info.getMessageCount(), info.getConsumerCount(), true);
        }
    }

    // ---- colas ----

    /**
     * Declara una cola. Con {@code conDlq}, declara tambien {@code <nombre>.dlq}
     * y enruta los descartes de la principal hacia ella por el exchange de
     * descartes del sistema, igual que hace la topologia declarada.
     */
    public EstadoCola crearCola(String nombre, boolean durable, boolean conDlq) {
        protegerDelSistema(nombre);
        QueueBuilder builder = durable ? QueueBuilder.durable(nombre) : QueueBuilder.nonDurable(nombre);
        if (conDlq) {
            String dlq = nombre + ".dlq";
            declarar(() -> admin.declareQueue(durable ? QueueBuilder.durable(dlq).build() : QueueBuilder.nonDurable(dlq).build()),
                    "la cola de descartes " + dlq);
            declarar(() -> admin.declareBinding(new Binding(dlq, Binding.DestinationType.QUEUE,
                            TopologiaRabbit.EXCHANGE_DLX, dlq, null)),
                    "el binding de " + dlq);
            builder.deadLetterExchange(TopologiaRabbit.EXCHANGE_DLX).deadLetterRoutingKey(dlq);
        }
        Queue cola = builder.build();
        declarar(() -> admin.declareQueue(cola), "la cola " + nombre);
        log.info("[admin] cola declarada {} (durable={}, dlq={})", nombre, durable, conDlq);
        return estado(nombre);
    }

    /**
     * Elimina una cola. Falla con 404 si no existe: borrar algo inexistente
     * casi siempre es un nombre mal escrito, y callarlo deja al operador
     * creyendo que limpio lo que no limpio.
     */
    public void eliminarCola(String nombre) {
        protegerDelSistema(nombre);
        if (admin.getQueueInfo(nombre) == null) {
            throw new AdministracionInvalidaException(Motivo.NO_EXISTE, "La cola " + nombre + " no existe");
        }
        declarar(() -> admin.deleteQueue(nombre), "el borrado de la cola " + nombre);
        log.info("[admin] cola eliminada {}", nombre);
    }

    /** Vacia una cola sin borrarla: util para dejar una DLQ en cero tras reprocesar. */
    public void purgarCola(String nombre) {
        if (admin.getQueueInfo(nombre) == null) {
            throw new AdministracionInvalidaException(Motivo.NO_EXISTE, "La cola " + nombre + " no existe");
        }
        declarar(() -> admin.purgeQueue(nombre), "el purgado de la cola " + nombre);
        log.info("[admin] cola purgada {}", nombre);
    }

    public EstadoCola estado(String nombre) {
        return EstadoCola.de(nombre, admin.getQueueInfo(nombre));
    }

    // ---- topologia del sistema ----

    public record EstadoFlujo(String proposito, String routingKeyDirect, String routingKeyTopic,
                              EstadoCola cola, EstadoCola dlq) {
    }

    public record Topologia(List<String> exchanges, List<EstadoFlujo> flujos, long totalEnDlq) {
    }

    /**
     * Lo declarado y lo que el broker reporta ahora (mensajes, consumidores).
     * {@code totalEnDlq} mayor que cero es la alarma: hay comandos que fallaron.
     */
    public Topologia topologia() {
        List<EstadoFlujo> flujos = TopologiaRabbit.FLUJOS.stream()
                .map(f -> new EstadoFlujo(f.proposito(), f.routingKeyDirect(), f.routingKeyTopic(),
                        estado(f.cola()), estado(f.dlq())))
                .toList();
        long enDlq = flujos.stream()
                .map(f -> f.dlq().mensajes())
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
        return new Topologia(
                List.of(TopologiaRabbit.EXCHANGE_DIRECT, TopologiaRabbit.EXCHANGE_TOPIC, TopologiaRabbit.EXCHANGE_DLX),
                flujos, enDlq);
    }

    // ---- exchanges ----

    public void crearExchange(String nombre, TipoExchange tipo, boolean durable) {
        protegerDelSistema(nombre);
        Exchange exchange = switch (tipo) {
            case DIRECT -> new DirectExchange(nombre, durable, false);
            case TOPIC -> new TopicExchange(nombre, durable, false);
            case FANOUT -> new FanoutExchange(nombre, durable, false);
        };
        declarar(() -> admin.declareExchange(exchange), "el exchange " + nombre);
        log.info("[admin] exchange declarado {} ({}, durable={})", nombre, tipo, durable);
    }

    public void eliminarExchange(String nombre) {
        protegerDelSistema(nombre);
        if (!admin.deleteExchange(nombre)) {
            throw new AdministracionInvalidaException(Motivo.NO_EXISTE, "El exchange " + nombre + " no existe");
        }
        log.info("[admin] exchange eliminado {}", nombre);
    }

    // ---- bindings ----

    /**
     * Une una cola a un exchange con una routing key. La cola y el exchange
     * deben existir: RabbitMQ aceptaria crear el binding igual, pero dejaria
     * una union a un destino fantasma que nadie nota hasta que faltan mensajes.
     */
    public void crearBinding(String cola, String exchange, String routingKey) {
        protegerDelSistema(cola);
        exigirCola(cola);
        declarar(() -> admin.declareBinding(binding(cola, exchange, routingKey)),
                "el binding " + exchange + " -> " + cola);
        log.info("[admin] binding declarado {} -> {} ({})", exchange, cola, routingKey);
    }

    public void eliminarBinding(String cola, String exchange, String routingKey) {
        protegerDelSistema(cola);
        exigirCola(cola);
        declarar(() -> admin.removeBinding(binding(cola, exchange, routingKey)),
                "el borrado del binding " + exchange + " -> " + cola);
        log.info("[admin] binding eliminado {} -> {} ({})", exchange, cola, routingKey);
    }

    /** Un binding se identifica por cola, exchange y routing key; el tipo del exchange no importa aqui. */
    private static Binding binding(String cola, String exchange, String routingKey) {
        return new Binding(cola, Binding.DestinationType.QUEUE, exchange, routingKey, null);
    }

    // ---- reglas ----

    /** Colas, DLQ y exchanges que declara el sistema: se pueden mirar, no tocar. */
    public static Set<String> recursosDelSistema() {
        Set<String> protegidos = new LinkedHashSet<>(Set.of(
                TopologiaRabbit.EXCHANGE_DIRECT, TopologiaRabbit.EXCHANGE_TOPIC, TopologiaRabbit.EXCHANGE_DLX));
        for (TopologiaRabbit.Flujo f : TopologiaRabbit.FLUJOS) {
            protegidos.add(f.cola());
            protegidos.add(f.dlq());
        }
        return protegidos;
    }

    private void protegerDelSistema(String nombre) {
        if (recursosDelSistema().contains(nombre)) {
            throw new AdministracionInvalidaException(Motivo.RECURSO_DEL_SISTEMA,
                    nombre + " es parte de la topologia del sistema y se administra desde el codigo, no por la API");
        }
    }

    private void exigirCola(String cola) {
        if (admin.getQueueInfo(cola) == null) {
            throw new AdministracionInvalidaException(Motivo.NO_EXISTE, "La cola " + cola + " no existe");
        }
    }

    /**
     * Traduce los fallos del broker. Spring AMQP los envuelve en
     * {@link AmqpException}; sin esto, un "ya existe con otros atributos"
     * saldria como 500 y parecerian un error del servicio.
     */
    private void declarar(Runnable operacion, String que) {
        try {
            operacion.run();
        } catch (AmqpException e) {
            String causa = causas(e);
            Motivo motivo;
            if (causa != null && causa.contains("PRECONDITION_FAILED")) {
                motivo = Motivo.CONFLICTO_CON_EXISTENTE;
            } else if (causa != null && causa.contains("NOT_FOUND")) {
                motivo = Motivo.NO_EXISTE;
            } else {
                motivo = Motivo.BROKER_RECHAZO;
            }
            log.warn("[admin] el broker rechazo {}: {}", que, causa);
            throw new AdministracionInvalidaException(motivo, "RabbitMQ rechazo " + que + ": " + causa);
        }
    }

    /**
     * El motivo real (PRECONDITION_FAILED, NOT_FOUND) viene varias capas abajo,
     * dentro del ShutdownSignalException del canal: se juntan todos los mensajes.
     */
    private static String causas(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null && sb.length() < 2000; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null) {
                if (!sb.isEmpty()) {
                    sb.append(" | ");
                }
                sb.append(t.getMessage());
            }
        }
        return sb.toString();
    }
}
