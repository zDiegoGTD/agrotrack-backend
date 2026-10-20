package cl.agrotrack.notify.infraestructura.mensajeria;

import cl.agrotrack.notify.aplicacion.Envelope;
import cl.agrotrack.notify.aplicacion.MensajeInvalidoException;
import cl.agrotrack.notify.aplicacion.ProcesadosStore;
import cl.agrotrack.notify.config.PropiedadesMensajeria;
import cl.agrotrack.notify.infraestructura.mensajeria.ProcesadorComandos.Resultado;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Cada ruta de confirmacion, contra un Channel simulado: que se hace ACK, que
 * se rechaza sin requeue (DLQ) y que se reintenta, y cuantas veces.
 */
class ProcesadorComandosTest {

    private static final long TAG = 42L;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private Channel canal;
    private ProcesadosStore procesados;
    private SimpleMeterRegistry registry;
    private ProcesadorComandos procesador;

    @BeforeEach
    void preparar() {
        canal = mock(Channel.class);
        procesados = new ProcesadosStore();
        registry = new SimpleMeterRegistry();
        // Esperas minimas para que el test no duerma segundos: 2 esperas = 3 intentos.
        var politica = new PropiedadesMensajeria(List.of(Duration.ofMillis(1), Duration.ofMillis(1)));
        procesador = new ProcesadorComandos(json, procesados, politica, registry);
    }

    private Message mensaje(String cuerpo) {
        Message m = MessageBuilder.withBody(cuerpo.getBytes(StandardCharsets.UTF_8)).build();
        m.getMessageProperties().setConsumerQueue("q.cmd.receipt");
        return m;
    }

    private Message comando(String eventId) throws Exception {
        Envelope env = new Envelope("1.0", "receipt.ticket", eventId, Instant.parse("2026-10-19T12:00:00Z"),
                "trace-1", "corr-1", "ms-agrotrack-deliveries", "DEL-2026-000001",
                new Envelope.Actor("op-1", "Jefa", "OPERADOR"), Map.of("plantilla", "ticket-recepcion"));
        return mensaje(json.writeValueAsString(env));
    }

    private double enDlq() {
        return registry.get("agrotrack.notify.dlq.total").counter().count();
    }

    @Test
    @DisplayName("Exito: un ACK explicito y ningun NACK")
    void exitoHaceAck() throws Exception {
        Resultado r = procesador.procesar(comando("e-1"), canal, TAG, cmd -> { });

        assertThat(r).isEqualTo(Resultado.PROCESADO);
        verify(canal).basicAck(TAG, false);
        verify(canal, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertThat(enDlq()).isZero();
    }

    @Test
    @DisplayName("Reentrega del mismo eventId: ACK sin ejecutar la accion otra vez")
    void duplicadoHaceAckSinEjecutar() throws Exception {
        AtomicInteger ejecuciones = new AtomicInteger();
        Consumer<Envelope> accion = cmd -> ejecuciones.incrementAndGet();

        procesador.procesar(comando("e-dup"), canal, 1L, accion);
        Resultado r = procesador.procesar(comando("e-dup"), canal, 2L, accion);

        assertThat(r).isEqualTo(Resultado.DUPLICADO);
        assertThat(ejecuciones).hasValue(1);
        verify(canal).basicAck(2L, false);
    }

    @Test
    @DisplayName("JSON invalido: NACK sin requeue de inmediato (DLQ), sin reintentos, y cuenta en la metrica")
    void invalidoVaDirectoALaDlq() throws Exception {
        AtomicInteger ejecuciones = new AtomicInteger();

        Resultado r = procesador.procesar(mensaje("esto no es json {"), canal, TAG, cmd -> ejecuciones.incrementAndGet());

        assertThat(r).isEqualTo(Resultado.INVALIDO_A_DLQ);
        verify(canal).basicNack(TAG, false, false);
        verify(canal, never()).basicAck(anyLong(), anyBoolean());
        assertThat(ejecuciones).hasValue(0);
        assertThat(enDlq()).isEqualTo(1);
    }

    @Test
    @DisplayName("Mensaje sin eventId: tambien es invalido y va directo a la DLQ")
    void sinEventId() throws Exception {
        Resultado r = procesador.procesar(mensaje("{\"type\":\"receipt.ticket\",\"subject\":\"DEL-1\"}"),
                canal, TAG, cmd -> { });

        assertThat(r).isEqualTo(Resultado.INVALIDO_A_DLQ);
        verify(canal).basicNack(TAG, false, false);
    }

    @Test
    @DisplayName("Fallo transitorio que se recupera al segundo intento: ACK, sin pasar por la DLQ")
    void transitorioSeRecupera() throws Exception {
        AtomicInteger intentos = new AtomicInteger();

        Resultado r = procesador.procesar(comando("e-2"), canal, TAG, cmd -> {
            if (intentos.incrementAndGet() == 1) {
                throw new IllegalStateException("SMTP caido");
            }
        });

        assertThat(r).isEqualTo(Resultado.PROCESADO);
        assertThat(intentos).hasValue(2);
        verify(canal).basicAck(TAG, false);
        verify(canal, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertThat(enDlq()).isZero();
    }

    @Test
    @DisplayName("Fallo persistente: 3 intentos y recien entonces NACK sin requeue (DLQ)")
    void persistenteAgotaYVaALaDlq() throws Exception {
        AtomicInteger intentos = new AtomicInteger();

        Resultado r = procesador.procesar(comando("e-3"), canal, TAG, cmd -> {
            intentos.incrementAndGet();
            throw new IllegalStateException("disco lleno");
        });

        assertThat(r).isEqualTo(Resultado.AGOTADO_A_DLQ);
        assertThat(intentos).hasValue(3);
        verify(canal).basicNack(TAG, false, false);
        verify(canal, never()).basicAck(anyLong(), anyBoolean());
        assertThat(enDlq()).isEqualTo(1);
    }

    @Test
    @DisplayName("Tras agotar reintentos el eventId queda desmarcado: reprocesar desde la DLQ ejecuta de verdad")
    void agotadoSeDesmarca() throws Exception {
        procesador.procesar(comando("e-4"), canal, 1L, cmd -> { throw new IllegalStateException("x"); });

        AtomicInteger ejecuciones = new AtomicInteger();
        Resultado r = procesador.procesar(comando("e-4"), canal, 2L, cmd -> ejecuciones.incrementAndGet());

        assertThat(r).isEqualTo(Resultado.PROCESADO);
        assertThat(ejecuciones).hasValue(1);
    }

    @Test
    @DisplayName("El servicio declara invalido el contenido: DLQ sin reintentar")
    void invalidoDetectadoPorElServicio() throws Exception {
        AtomicInteger intentos = new AtomicInteger();

        Resultado r = procesador.procesar(comando("e-5"), canal, TAG, cmd -> {
            intentos.incrementAndGet();
            throw new MensajeInvalidoException("plantilla desconocida");
        });

        assertThat(r).isEqualTo(Resultado.INVALIDO_A_DLQ);
        assertThat(intentos).hasValue(1);
        verify(canal).basicNack(TAG, false, false);
    }

    @Test
    @DisplayName("Si el hilo se interrumpe durante la espera (apagado): NACK con requeue, no a la DLQ")
    void apagadoDevuelveALaCola() throws Exception {
        Thread.currentThread().interrupt(); // el sleep del primer reintento lanzara InterruptedException
        try {
            Resultado r = procesador.procesar(comando("e-6"), canal, TAG, cmd -> {
                throw new IllegalStateException("fallo");
            });

            assertThat(r).isEqualTo(Resultado.DEVUELTO_A_LA_COLA);
            verify(canal).basicNack(TAG, false, true);
            assertThat(enDlq()).isZero();
        } finally {
            Thread.interrupted(); // limpiar la marca para no afectar otros tests
        }
    }
}
