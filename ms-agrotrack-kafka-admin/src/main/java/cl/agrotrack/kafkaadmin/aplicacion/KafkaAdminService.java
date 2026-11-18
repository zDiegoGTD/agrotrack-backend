package cl.agrotrack.kafkaadmin.aplicacion;

import cl.agrotrack.kafkaadmin.aplicacion.AdministracionInvalidaException.Motivo;
import cl.agrotrack.kafkaadmin.config.TopologiaKafka;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.GroupNotEmptyException;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.InvalidPartitionsException;
import org.apache.kafka.common.errors.InvalidReplicationFactorException;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.PolicyViolationException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownMemberIdException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Toda la administracion de Kafka: topicos, su configuracion y los consumer
 * groups. Los controladores solo traducen HTTP; ninguno usa AdminClient.
 *
 * <p>Los topicos del sistema ({@link TopologiaKafka}) estan protegidos: no se
 * borran ni se reparticionan (cambiar las particiones rompe el orden por
 * clave de los eventos de cada entrega). Solo se les puede ajustar la
 * retencion, que es mantenimiento normal.
 */
@Service
public class KafkaAdminService {

    private static final Logger log = LoggerFactory.getLogger(KafkaAdminService.class);
    private static final long TIMEOUT_S = 15;

    /** Se muestran siempre, aunque esten en su valor por defecto. */
    private static final Set<String> CONFIG_PRINCIPAL = Set.of(
            TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.RETENTION_MS_CONFIG, TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG);

    /** Lo unico que se puede cambiar en un topico del sistema. */
    private static final Set<String> AJUSTABLE_EN_SISTEMA = Set.of(TopicConfig.RETENTION_MS_CONFIG);

    public record EstadoTopico(String nombre, int particiones, int replicas,
                               Map<String, String> configuracion, boolean delSistema) {
    }

    public record EstadoGrupo(String grupo, String estado, int miembros,
                              Map<String, Long> lagPorTopico, long lagTotal) {
    }

    public enum Posicion { INICIO, FIN }

    private final AdminClient admin;
    private final Set<String> delSistema;

    public KafkaAdminService(KafkaAdmin kafkaAdmin, TopologiaKafka topologia) {
        this.admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        this.delSistema = TopologiaKafka.definiciones(topologia.particiones()).stream()
                .map(TopologiaKafka.Definicion::nombre)
                .collect(Collectors.toUnmodifiableSet());
    }

    @PreDestroy
    void cerrar() {
        admin.close();
    }

    public Set<String> recursosDelSistema() {
        return delSistema;
    }

    // ---- topicos ----

    public List<EstadoTopico> topicos() {
        Set<String> nombres = esperar(admin.listTopics().names());
        return describir(nombres).values().stream()
                .sorted((a, b) -> a.nombre().compareTo(b.nombre()))
                .toList();
    }

    public EstadoTopico topico(String nombre) {
        return describir(Set.of(nombre)).get(nombre);
    }

    public EstadoTopico crearTopico(String nombre, int particiones, short replicas, Map<String, String> configuracion) {
        NewTopic nuevo = new NewTopic(nombre, particiones, replicas)
                .configs(configuracion == null ? Map.of() : configuracion);
        esperar(admin.createTopics(List.of(nuevo)).all());
        log.info("[kafka-admin] topico creado {} ({} particiones, {} replicas, config {})",
                nombre, particiones, replicas, configuracion);
        return topicoRecienCreado(nombre);
    }

    public void eliminarTopico(String nombre) {
        protegerSistema(nombre, "eliminar");
        esperar(admin.deleteTopics(List.of(nombre)).all());
        log.info("[kafka-admin] topico eliminado {}", nombre);
    }

    /** Valor vacio = volver al valor por defecto del broker. */
    public EstadoTopico cambiarConfiguracion(String nombre, Map<String, String> configuracion) {
        if (delSistema.contains(nombre) && !AJUSTABLE_EN_SISTEMA.containsAll(configuracion.keySet())) {
            throw new AdministracionInvalidaException(Motivo.RECURSO_DEL_SISTEMA,
                    nombre + " es un topico del sistema: solo se puede ajustar " + AJUSTABLE_EN_SISTEMA);
        }
        topico(nombre); // NO_EXISTE antes de intentar cambiar
        ConfigResource recurso = new ConfigResource(ConfigResource.Type.TOPIC, nombre);
        List<AlterConfigOp> ops = configuracion.entrySet().stream()
                .map(e -> e.getValue() == null || e.getValue().isBlank()
                        ? new AlterConfigOp(new ConfigEntry(e.getKey(), ""), AlterConfigOp.OpType.DELETE)
                        : new AlterConfigOp(new ConfigEntry(e.getKey(), e.getValue()), AlterConfigOp.OpType.SET))
                .toList();
        esperar(admin.incrementalAlterConfigs(Map.of(recurso, ops)).all());
        log.info("[kafka-admin] configuracion de {} cambiada: {}", nombre, configuracion);
        return topico(nombre);
    }

    /** Kafka solo permite aumentar: bajar particiones cambiaria a que particion va cada clave. */
    public EstadoTopico aumentarParticiones(String nombre, int particiones) {
        protegerSistema(nombre, "cambiar las particiones de");
        int actuales = topico(nombre).particiones();
        if (particiones <= actuales) {
            throw new AdministracionInvalidaException(Motivo.DATOS_INVALIDOS,
                    nombre + " tiene " + actuales + " particiones; solo se puede aumentar, no bajar a " + particiones);
        }
        esperar(admin.createPartitions(Map.of(nombre, NewPartitions.increaseTo(particiones))).all());
        log.info("[kafka-admin] {} pasa de {} a {} particiones", nombre, actuales, particiones);
        return topico(nombre);
    }

    // ---- consumer groups ----

    public List<EstadoGrupo> grupos() {
        Collection<ConsumerGroupListing> listados = esperar(admin.listConsumerGroups().all());
        return listados.stream()
                .map(ConsumerGroupListing::groupId)
                .sorted()
                .map(this::grupo)
                .toList();
    }

    public EstadoGrupo grupo(String grupo) {
        ConsumerGroupDescription d = esperar(admin.describeConsumerGroups(List.of(grupo)).all()).get(grupo);
        if (d == null || d.state() == ConsumerGroupState.DEAD) {
            throw new AdministracionInvalidaException(Motivo.NO_EXISTE, "El grupo " + grupo + " no existe");
        }
        Map<TopicPartition, OffsetAndMetadata> confirmados =
                esperar(admin.listConsumerGroupOffsets(grupo).partitionsToOffsetAndMetadata());
        Map<TopicPartition, Long> finales = offsets(confirmados.keySet(), OffsetSpec.latest());

        Map<String, Long> lagPorTopico = new TreeMap<>();
        confirmados.forEach((tp, om) -> {
            long lag = om == null ? 0 : Math.max(0, finales.getOrDefault(tp, 0L) - om.offset());
            lagPorTopico.merge(tp.topic(), lag, Long::sum);
        });
        long total = lagPorTopico.values().stream().mapToLong(Long::longValue).sum();
        return new EstadoGrupo(grupo, d.state().toString(), d.members().size(), lagPorTopico, total);
    }

    /**
     * Mueve los offsets del grupo en un topico al inicio (reprocesar todo) o
     * al fin (saltarse lo pendiente). Solo con el grupo sin consumidores:
     * Kafka no deja mover offsets de un grupo activo.
     */
    public EstadoGrupo reiniciarOffsets(String grupo, String topico, Posicion posicion) {
        EstadoGrupo actual = grupo(grupo);
        if (actual.miembros() > 0) {
            throw new AdministracionInvalidaException(Motivo.CONFLICTO_CON_EXISTENTE,
                    "El grupo " + grupo + " tiene " + actual.miembros() + " consumidores activos: detenerlos antes de mover offsets");
        }
        int particiones = topico(topico).particiones();
        List<TopicPartition> tps = java.util.stream.IntStream.range(0, particiones)
                .mapToObj(p -> new TopicPartition(topico, p)).toList();
        Map<TopicPartition, Long> destino = offsets(tps,
                posicion == Posicion.INICIO ? OffsetSpec.earliest() : OffsetSpec.latest());
        Map<TopicPartition, OffsetAndMetadata> nuevos = destino.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> new OffsetAndMetadata(e.getValue())));
        esperar(admin.alterConsumerGroupOffsets(grupo, nuevos).all());
        log.info("[kafka-admin] offsets de {} en {} movidos al {}", grupo, topico, posicion);
        return grupo(grupo);
    }

    // ---- internos ----

    private void protegerSistema(String nombre, String accion) {
        if (delSistema.contains(nombre)) {
            throw new AdministracionInvalidaException(Motivo.RECURSO_DEL_SISTEMA,
                    "No se puede " + accion + " " + nombre + ": es un topico del sistema (TopologiaKafka)");
        }
    }

    private Map<String, EstadoTopico> describir(Collection<String> nombres) {
        if (nombres.isEmpty()) {
            return Map.of();
        }
        Map<String, TopicDescription> descripciones = esperar(admin.describeTopics(nombres).allTopicNames());
        List<ConfigResource> recursos = nombres.stream()
                .map(n -> new ConfigResource(ConfigResource.Type.TOPIC, n)).toList();
        Map<ConfigResource, Config> configs = esperar(admin.describeConfigs(recursos).all());

        Map<String, EstadoTopico> salida = new LinkedHashMap<>();
        descripciones.forEach((nombre, td) -> {
            Config cfg = configs.get(new ConfigResource(ConfigResource.Type.TOPIC, nombre));
            Map<String, String> visibles = new TreeMap<>();
            if (cfg != null) {
                cfg.entries().stream()
                        .filter(e -> CONFIG_PRINCIPAL.contains(e.name()) || !e.isDefault())
                        .forEach(e -> visibles.put(e.name(), e.value()));
            }
            int replicas = td.partitions().isEmpty() ? 0 : td.partitions().get(0).replicas().size();
            salida.put(nombre, new EstadoTopico(nombre, td.partitions().size(), replicas, visibles,
                    delSistema.contains(nombre)));
        });
        return salida;
    }

    /** Recien creado, el controlador del cluster puede tardar un instante en describirlo. */
    private EstadoTopico topicoRecienCreado(String nombre) {
        AdministracionInvalidaException ultima = null;
        for (int intento = 0; intento < 10; intento++) {
            try {
                return topico(nombre);
            } catch (AdministracionInvalidaException e) {
                if (e.motivo() != Motivo.NO_EXISTE) {
                    throw e;
                }
                ultima = e;
                dormir(200);
            }
        }
        throw ultima;
    }

    private Map<TopicPartition, Long> offsets(Collection<TopicPartition> tps, OffsetSpec spec) {
        if (tps.isEmpty()) {
            return Map.of();
        }
        Map<TopicPartition, OffsetSpec> pedido = tps.stream().collect(Collectors.toMap(Function.identity(), tp -> spec));
        return esperar(admin.listOffsets(pedido).all()).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
    }

    private static <T> T esperar(KafkaFuture<T> futuro) {
        try {
            return futuro.get(TIMEOUT_S, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw traducir(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdministracionInvalidaException(Motivo.BROKER_RECHAZO, "Operacion interrumpida");
        } catch (TimeoutException e) {
            throw new AdministracionInvalidaException(Motivo.BROKER_RECHAZO,
                    "Kafka no respondio en " + TIMEOUT_S + " s");
        }
    }

    static AdministracionInvalidaException traducir(Throwable causa) {
        String msg = causa.getMessage();
        Motivo motivo;
        if (causa instanceof UnknownTopicOrPartitionException || causa instanceof GroupIdNotFoundException) {
            motivo = Motivo.NO_EXISTE;
        } else if (causa instanceof TopicExistsException || causa instanceof InvalidReplicationFactorException
                || causa instanceof PolicyViolationException || causa instanceof GroupNotEmptyException
                || causa instanceof UnknownMemberIdException) {
            motivo = Motivo.CONFLICTO_CON_EXISTENTE;
        } else if (causa instanceof InvalidPartitionsException || causa instanceof InvalidConfigurationException
                || causa instanceof InvalidRequestException || causa instanceof InvalidTopicException) {
            motivo = Motivo.DATOS_INVALIDOS;
        } else {
            motivo = Motivo.BROKER_RECHAZO;
        }
        log.warn("[kafka-admin] {} -> {}: {}", causa.getClass().getSimpleName(), motivo, msg);
        return new AdministracionInvalidaException(motivo, msg);
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
