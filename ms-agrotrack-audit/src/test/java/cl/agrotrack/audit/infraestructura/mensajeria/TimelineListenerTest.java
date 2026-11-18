package cl.agrotrack.audit.infraestructura.mensajeria;

import cl.agrotrack.audit.aplicacion.AuditService;
import cl.agrotrack.audit.aplicacion.Envelope;
import cl.agrotrack.audit.infraestructura.persistencia.EventoTimeline;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** El resumen es derivado: si no se puede publicar, el evento ya registrado no se reprocesa ni tumba el consumo. */
class TimelineListenerTest {

    @Test
    @DisplayName("Si kafka.send falla al instante (metadata, serializacion), onEvento no lanza")
    @SuppressWarnings("unchecked")
    void fallaSincronaDelEnvioNoEscapa() {
        AuditService audit = mock(AuditService.class);
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        EventoTimeline registrado = mock(EventoTimeline.class);
        when(registrado.getEntregaCodigo()).thenReturn("DEL-1");
        when(registrado.getTipo()).thenReturn("delivery.received");
        when(registrado.getPayload()).thenReturn(Map.of("estado", "RECIBIDA"));
        when(registrado.getOcurridoEn()).thenReturn(Instant.parse("2026-09-07T12:00:00Z"));
        when(audit.registrar(any(Envelope.class))).thenReturn(Optional.of(registrado));
        when(kafka.send(eq("audit.timeline"), anyString(), any())).thenThrow(new TimeoutException("metadata"));

        TimelineListener listener = new TimelineListener(audit, kafka, "audit.timeline");

        assertThatCode(() -> listener.onEvento(mock(Envelope.class))).doesNotThrowAnyException();
    }
}
