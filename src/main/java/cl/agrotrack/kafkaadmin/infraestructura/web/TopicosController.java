package cl.agrotrack.kafkaadmin.infraestructura.web;

import cl.agrotrack.kafkaadmin.config.TopologiaKafka;
import cl.agrotrack.kafkaadmin.config.TopologiaKafka.Definicion;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Lo declarado y lo que el cluster reporta ahora. */
@RestController
@RequestMapping("/api/kafka")
public class TopicosController {

    private final KafkaAdmin kafkaAdmin;
    private final TopologiaKafka topologia;

    public TopicosController(KafkaAdmin kafkaAdmin, TopologiaKafka topologia) {
        this.kafkaAdmin = kafkaAdmin;
        this.topologia = topologia;
    }

    public record EstadoTopico(String nombre, String proposito,
                               Integer particiones, Integer replicas,
                               String politica, Long retencionMs, String minInsyncReplicas,
                               boolean existe) {
    }

    @GetMapping("/topics")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public List<EstadoTopico> topicos() throws Exception {
        List<Definicion> defs = TopologiaKafka.definiciones(topologia.particiones());
        List<String> nombres = defs.stream().map(Definicion::nombre).toList();

        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Map<String, TopicDescription> descripciones = admin.describeTopics(nombres)
                    .allTopicNames().get(10, TimeUnit.SECONDS);
            Map<ConfigResource, Config> configs = admin.describeConfigs(
                            nombres.stream().map(n -> new ConfigResource(ConfigResource.Type.TOPIC, n)).toList())
                    .all().get(10, TimeUnit.SECONDS);

            return defs.stream().map(d -> {
                TopicDescription td = descripciones.get(d.nombre());
                Config cfg = configs.get(new ConfigResource(ConfigResource.Type.TOPIC, d.nombre()));
                if (td == null || cfg == null) {
                    return new EstadoTopico(d.nombre(), d.proposito(), null, null, null, null, null, false);
                }
                return new EstadoTopico(
                        d.nombre(), d.proposito(),
                        td.partitions().size(),
                        td.partitions().get(0).replicas().size(),
                        cfg.get(TopicConfig.CLEANUP_POLICY_CONFIG).value(),
                        Long.valueOf(cfg.get(TopicConfig.RETENTION_MS_CONFIG).value()),
                        cfg.get(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG).value(),
                        true);
            }).toList();
        }
    }
}
