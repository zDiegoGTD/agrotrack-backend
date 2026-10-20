package cl.agrotrack.mqadmin.aplicacion;

import cl.agrotrack.mqadmin.aplicacion.AdministracionInvalidaException.Motivo;
import cl.agrotrack.mqadmin.aplicacion.RabbitAdminService.TipoExchange;
import cl.agrotrack.mqadmin.config.TopologiaRabbit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RabbitAdminServiceTest {

    private AmqpAdmin admin;
    private RabbitAdminService servicio;

    @BeforeEach
    void preparar() {
        admin = mock(AmqpAdmin.class);
        servicio = new RabbitAdminService(admin);
    }

    // ---- colas ----

    @Test
    @DisplayName("Crear una cola con DLQ declara la DLQ, su binding al DLX y la principal apuntando a ella")
    void crearColaConDlq() {
        when(admin.getQueueInfo("q.prueba")).thenReturn(new QueueInformation("q.prueba", 0, 0));

        var estado = servicio.crearCola("q.prueba", true, true);

        ArgumentCaptor<Queue> colas = ArgumentCaptor.forClass(Queue.class);
        verify(admin, times(2)).declareQueue(colas.capture());
        List<Queue> declaradas = colas.getAllValues();
        assertThat(declaradas.get(0).getName()).isEqualTo("q.prueba.dlq");
        Queue principal = declaradas.get(1);
        assertThat(principal.getName()).isEqualTo("q.prueba");
        assertThat(principal.isDurable()).isTrue();
        assertThat(principal.getArguments())
                .containsEntry("x-dead-letter-exchange", TopologiaRabbit.EXCHANGE_DLX)
                .containsEntry("x-dead-letter-routing-key", "q.prueba.dlq");

        ArgumentCaptor<Binding> binding = ArgumentCaptor.forClass(Binding.class);
        verify(admin).declareBinding(binding.capture());
        assertThat(binding.getValue().getExchange()).isEqualTo(TopologiaRabbit.EXCHANGE_DLX);
        assertThat(binding.getValue().getDestination()).isEqualTo("q.prueba.dlq");

        assertThat(estado.existe()).isTrue();
    }

    @Test
    @DisplayName("Sin DLQ se declara solo la cola, sin argumentos de descarte")
    void crearColaSinDlq() {
        servicio.crearCola("q.simple", false, false);

        ArgumentCaptor<Queue> cola = ArgumentCaptor.forClass(Queue.class);
        verify(admin).declareQueue(cola.capture());
        assertThat(cola.getValue().isDurable()).isFalse();
        assertThat(cola.getValue().getArguments()).doesNotContainKey("x-dead-letter-exchange");
        verify(admin, never()).declareBinding(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"q.cmd.email", "q.cmd.receipt.dlq", "cmd.direct", "cmd.topic", "cmd.dead.dlx"})
    @DisplayName("Los recursos de la topologia del sistema no se crean ni se borran por API")
    void protegeLoDelSistema(String nombre) {
        assertThatThrownBy(() -> servicio.eliminarCola(nombre))
                .isInstanceOf(AdministracionInvalidaException.class)
                .extracting(e -> ((AdministracionInvalidaException) e).motivo())
                .isEqualTo(Motivo.RECURSO_DEL_SISTEMA);
        assertThatThrownBy(() -> servicio.eliminarExchange(nombre))
                .isInstanceOf(AdministracionInvalidaException.class);
        assertThatThrownBy(() -> servicio.crearCola(nombre, true, true))
                .isInstanceOf(AdministracionInvalidaException.class);
        verify(admin, never()).deleteQueue(anyString());
        verify(admin, never()).deleteExchange(anyString());
        verify(admin, never()).declareQueue(any());
    }

    @Test
    @DisplayName("Eliminar una cola que no existe responde NO_EXISTE en vez de fingir que borro")
    void eliminarColaInexistente() {
        when(admin.getQueueInfo("q.fantasma")).thenReturn(null);

        assertThatThrownBy(() -> servicio.eliminarCola("q.fantasma"))
                .isInstanceOf(AdministracionInvalidaException.class)
                .extracting(e -> ((AdministracionInvalidaException) e).motivo())
                .isEqualTo(Motivo.NO_EXISTE);
        verify(admin, never()).deleteQueue(anyString());
    }

    @Test
    @DisplayName("Eliminar una cola existente la borra")
    void eliminarCola() {
        when(admin.getQueueInfo("q.vieja")).thenReturn(new QueueInformation("q.vieja", 3, 0));

        servicio.eliminarCola("q.vieja");

        verify(admin).deleteQueue("q.vieja");
    }

    @Test
    @DisplayName("Purgar vacia la cola sin borrarla; se permite tambien en DLQ del sistema")
    void purgar() {
        when(admin.getQueueInfo("q.cmd.email.dlq")).thenReturn(new QueueInformation("q.cmd.email.dlq", 5, 0));

        servicio.purgarCola("q.cmd.email.dlq");

        verify(admin).purgeQueue("q.cmd.email.dlq");
        verify(admin, never()).deleteQueue(anyString());
    }

    @Test
    @DisplayName("Si el broker dice PRECONDITION_FAILED (ya existe con otros atributos) se traduce a conflicto")
    void conflictoConExistente() {
        when(admin.declareQueue(any(Queue.class))).thenThrow(new AmqpIOException(
                new IOException("channel error; reply-code=406, reply-text=PRECONDITION_FAILED - inequivalent arg 'durable'")));

        assertThatThrownBy(() -> servicio.crearCola("q.choca", true, false))
                .isInstanceOf(AdministracionInvalidaException.class)
                .extracting(e -> ((AdministracionInvalidaException) e).motivo())
                .isEqualTo(Motivo.CONFLICTO_CON_EXISTENTE);
    }

    // ---- exchanges ----

    @Test
    @DisplayName("Crear un exchange topic declara exactamente un TopicExchange durable")
    void crearExchangeTopic() {
        servicio.crearExchange("ex.pruebas", TipoExchange.TOPIC, true);

        ArgumentCaptor<Exchange> ex = ArgumentCaptor.forClass(Exchange.class);
        verify(admin).declareExchange(ex.capture());
        assertThat(ex.getValue().getName()).isEqualTo("ex.pruebas");
        assertThat(ex.getValue().getType()).isEqualTo("topic");
        assertThat(ex.getValue().isDurable()).isTrue();
    }

    @Test
    @DisplayName("Eliminar un exchange inexistente responde NO_EXISTE")
    void eliminarExchangeInexistente() {
        when(admin.deleteExchange("ex.fantasma")).thenReturn(false);

        assertThatThrownBy(() -> servicio.eliminarExchange("ex.fantasma"))
                .isInstanceOf(AdministracionInvalidaException.class)
                .extracting(e -> ((AdministracionInvalidaException) e).motivo())
                .isEqualTo(Motivo.NO_EXISTE);
    }

    // ---- bindings ----

    @Test
    @DisplayName("Crear un binding exige que la cola exista y declara cola+exchange+routing key")
    void crearBinding() {
        when(admin.getQueueInfo("q.prueba")).thenReturn(new QueueInformation("q.prueba", 0, 0));

        servicio.crearBinding("q.prueba", "ex.pruebas", "pruebas.*");

        ArgumentCaptor<Binding> b = ArgumentCaptor.forClass(Binding.class);
        verify(admin).declareBinding(b.capture());
        assertThat(b.getValue().getDestination()).isEqualTo("q.prueba");
        assertThat(b.getValue().getExchange()).isEqualTo("ex.pruebas");
        assertThat(b.getValue().getRoutingKey()).isEqualTo("pruebas.*");
    }

    @Test
    @DisplayName("Un binding hacia una cola inexistente se rechaza antes de llegar al broker")
    void bindingAColaInexistente() {
        when(admin.getQueueInfo("q.fantasma")).thenReturn(null);

        assertThatThrownBy(() -> servicio.crearBinding("q.fantasma", "ex.pruebas", "x"))
                .isInstanceOf(AdministracionInvalidaException.class);
        verify(admin, never()).declareBinding(any());
    }

    @Test
    @DisplayName("Si el exchange del binding no existe, el NOT_FOUND del broker se traduce a NO_EXISTE")
    void bindingAExchangeInexistente() {
        when(admin.getQueueInfo("q.prueba")).thenReturn(new QueueInformation("q.prueba", 0, 0));
        org.mockito.Mockito.doThrow(new AmqpIOException(
                        new IOException("channel error; reply-code=404, reply-text=NOT_FOUND - no exchange 'ex.no'")))
                .when(admin).declareBinding(any());

        assertThatThrownBy(() -> servicio.crearBinding("q.prueba", "ex.no", "x"))
                .isInstanceOf(AdministracionInvalidaException.class)
                .extracting(e -> ((AdministracionInvalidaException) e).motivo())
                .isEqualTo(Motivo.NO_EXISTE);
    }

    @Test
    @DisplayName("Los bindings de las colas del sistema tampoco se tocan por API")
    void bindingDelSistema() {
        assertThatThrownBy(() -> servicio.eliminarBinding("q.cmd.email", "cmd.direct", "email.send"))
                .isInstanceOf(AdministracionInvalidaException.class)
                .extracting(e -> ((AdministracionInvalidaException) e).motivo())
                .isEqualTo(Motivo.RECURSO_DEL_SISTEMA);
        verify(admin, never()).removeBinding(any());
    }

    // ---- topologia ----

    @Test
    @DisplayName("La topologia suma los mensajes en DLQ: mayor que cero es la alarma")
    void topologiaSumaDlq() {
        when(admin.getQueueInfo(anyString())).thenAnswer(inv -> {
            String nombre = inv.getArgument(0);
            return new QueueInformation(nombre, nombre.endsWith(".dlq") ? 2 : 0, 1);
        });

        var t = servicio.topologia();

        assertThat(t.flujos()).hasSize(3);
        assertThat(t.totalEnDlq()).isEqualTo(6);
        assertThat(t.exchanges()).containsExactly("cmd.direct", "cmd.topic", "cmd.dead.dlx");
    }
}
