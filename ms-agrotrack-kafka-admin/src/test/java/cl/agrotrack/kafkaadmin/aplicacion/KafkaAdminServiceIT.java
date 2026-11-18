package cl.agrotrack.kafkaadmin.aplicacion;

import cl.agrotrack.kafkaadmin.aplicacion.AdministracionInvalidaException.Motivo;
import cl.agrotrack.kafkaadmin.aplicacion.KafkaAdminService.EstadoGrupo;
import cl.agrotrack.kafkaadmin.aplicacion.KafkaAdminService.EstadoTopico;
import cl.agrotrack.kafkaadmin.aplicacion.KafkaAdminService.Posicion;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El servicio contra un Kafka real de 1 broker: lo que pide la API pasa de verdad en el cluster. */
@SpringBootTest(properties = "agrotrack.kafka.replicas=1")
@Testcontainers
class KafkaAdminServiceIT {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
            .withKraft();

    @Autowired KafkaAdminService servicio;

    private final String nombre = "demo." + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void limpiar() {
        try {
            servicio.eliminarTopico(nombre);
        } catch (AdministracionInvalidaException ignorado) {
            // el test ya lo borro o nunca lo creo
        }
    }

    @Test
    @DisplayName("Crea un topico con su configuracion, se lee de vuelta y despues se elimina")
    void creaYElimina() {
        EstadoTopico creado = servicio.crearTopico(nombre, 2, (short) 1,
                Map.of("retention.ms", "3600000", "cleanup.policy", "compact"));

        assertThat(creado.particiones()).isEqualTo(2);
        assertThat(creado.replicas()).isEqualTo(1);
        assertThat(creado.configuracion()).containsEntry("retention.ms", "3600000")
                .containsEntry("cleanup.policy", "compact");
        assertThat(creado.delSistema()).isFalse();
        assertThat(servicio.topicos()).extracting(EstadoTopico::nombre).contains(nombre, "deliveries.events");

        servicio.eliminarTopico(nombre);
        assertThatThrownBy(() -> servicio.topico(nombre))
                .isInstanceOfSatisfying(AdministracionInvalidaException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.NO_EXISTE));
    }

    @Test
    @DisplayName("Crear dos veces el mismo topico: CONFLICTO")
    void yaExiste() {
        servicio.crearTopico(nombre, 1, (short) 1, Map.of());
        assertThatThrownBy(() -> servicio.crearTopico(nombre, 1, (short) 1, Map.of()))
                .isInstanceOfSatisfying(AdministracionInvalidaException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.CONFLICTO_CON_EXISTENTE));
    }

    @Test
    @DisplayName("Cambia la retencion de un topico existente")
    void cambiaRetencion() {
        servicio.crearTopico(nombre, 1, (short) 1, Map.of("retention.ms", "3600000"));

        EstadoTopico cambiado = servicio.cambiarConfiguracion(nombre, Map.of("retention.ms", "7200000"));

        assertThat(cambiado.configuracion()).containsEntry("retention.ms", "7200000");
    }

    @Test
    @DisplayName("Una clave de configuracion que Kafka no conoce: DATOS_INVALIDOS")
    void configuracionDesconocida() {
        servicio.crearTopico(nombre, 1, (short) 1, Map.of());
        assertThatThrownBy(() -> servicio.cambiarConfiguracion(nombre, Map.of("no.existe", "1")))
                .isInstanceOfSatisfying(AdministracionInvalidaException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.DATOS_INVALIDOS));
    }

    @Test
    @DisplayName("Aumenta particiones de 1 a 3; reducir o dejar igual es DATOS_INVALIDOS")
    void particiones() {
        servicio.crearTopico(nombre, 1, (short) 1, Map.of());

        assertThat(servicio.aumentarParticiones(nombre, 3).particiones()).isEqualTo(3);
        assertThatThrownBy(() -> servicio.aumentarParticiones(nombre, 2))
                .isInstanceOfSatisfying(AdministracionInvalidaException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.DATOS_INVALIDOS));
    }

    @Test
    @DisplayName("Los topicos del sistema no se borran ni se les cambian particiones; solo la retencion")
    void topicosDelSistema() {
        assertThat(servicio.recursosDelSistema()).contains("deliveries.events", "audit.timeline", "deliveries.events.DLT");
        assertThatThrownBy(() -> servicio.eliminarTopico("deliveries.events"))
                .isInstanceOfSatisfying(AdministracionInvalidaException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.RECURSO_DEL_SISTEMA));
        assertThatThrownBy(() -> servicio.aumentarParticiones("deliveries.events", 6))
                .isInstanceOfSatisfying(AdministracionInvalidaException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.RECURSO_DEL_SISTEMA));
        assertThatThrownBy(() -> servicio.cambiarConfiguracion("deliveries.events", Map.of("cleanup.policy", "compact")))
                .isInstanceOfSatisfying(AdministracionInvalidaException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.RECURSO_DEL_SISTEMA));
        assertThat(servicio.topico("deliveries.events").delSistema()).isTrue();
    }

    @Test
    @DisplayName("Mas replicas que brokers: CONFLICTO con el motivo del broker")
    void replicasDeMas() {
        assertThatThrownBy(() -> servicio.crearTopico(nombre, 1, (short) 3, Map.of()))
                .isInstanceOfSatisfying(AdministracionInvalidaException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.CONFLICTO_CON_EXISTENTE));
    }

    @Test
    @DisplayName("Lag de un grupo: 5 producidos, 2 consumidos -> 3; al reiniciar al inicio -> 5; al fin -> 0")
    void lagYReinicio() {
        servicio.crearTopico(nombre, 1, (short) 1, Map.of());
        producir(5);
        String grupo = "grupo-" + nombre;
        consumirYCerrar(grupo, 2);

        EstadoGrupo antes = servicio.grupo(grupo);
        assertThat(antes.lagPorTopico()).containsEntry(nombre, 3L);
        assertThat(antes.lagTotal()).isEqualTo(3L);
        assertThat(servicio.grupos()).extracting(EstadoGrupo::grupo).contains(grupo);

        assertThat(servicio.reiniciarOffsets(grupo, nombre, Posicion.INICIO).lagTotal()).isEqualTo(5L);
        assertThat(servicio.reiniciarOffsets(grupo, nombre, Posicion.FIN).lagTotal()).isZero();
    }

    @Test
    @DisplayName("Grupo inexistente: NO_EXISTE")
    void grupoInexistente() {
        assertThatThrownBy(() -> servicio.grupo("no-existe-" + nombre))
                .isInstanceOfSatisfying(AdministracionInvalidaException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.NO_EXISTE));
    }

    @Test
    @DisplayName("Reiniciar offsets con un consumidor activo en el grupo: CONFLICTO")
    void reinicioConMiembroActivo() {
        servicio.crearTopico(nombre, 1, (short) 1, Map.of());
        producir(1);
        String grupo = "activo-" + nombre;
        try (KafkaConsumer<String, String> activo = consumidor(grupo)) {
            activo.subscribe(List.of(nombre));
            activo.poll(Duration.ofSeconds(5));

            assertThatThrownBy(() -> servicio.reiniciarOffsets(grupo, nombre, Posicion.INICIO))
                    .isInstanceOfSatisfying(AdministracionInvalidaException.class,
                            e -> assertThat(e.motivo()).isEqualTo(Motivo.CONFLICTO_CON_EXISTENTE));
        }
    }

    // ---- utilidades ----

    private void producir(int cantidad) {
        try (KafkaProducer<String, String> p = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            for (int i = 0; i < cantidad; i++) {
                p.send(new ProducerRecord<>(nombre, "k" + i, "v" + i));
            }
            p.flush();
        }
    }

    private void consumirYCerrar(String grupo, int cantidad) {
        try (KafkaConsumer<String, String> c = consumidor(grupo)) {
            c.subscribe(List.of(nombre));
            int leidos = 0;
            long limite = System.currentTimeMillis() + 15_000;
            while (leidos < cantidad && System.currentTimeMillis() < limite) {
                leidos += c.poll(Duration.ofMillis(500)).count();
            }
            assertThat(leidos).isGreaterThanOrEqualTo(cantidad);
            // Confirma exactamente "cantidad" (el primer poll pudo traer mas)
            c.commitSync(Map.of(new org.apache.kafka.common.TopicPartition(nombre, 0),
                    new org.apache.kafka.clients.consumer.OffsetAndMetadata(cantidad)));
        }
    }

    private KafkaConsumer<String, String> consumidor(String grupo) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, grupo,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }
}
