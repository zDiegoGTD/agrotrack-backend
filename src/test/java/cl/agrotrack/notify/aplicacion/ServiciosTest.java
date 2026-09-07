package cl.agrotrack.notify.aplicacion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Los tres ejecutores de comandos, sin broker. */
class ServiciosTest {

    static Envelope comando(String type, String plantilla) {
        return new Envelope("1.0", type, UUID.randomUUID().toString(), Instant.parse("2026-09-07T12:00:00Z"),
                "abc123", "corr-1", "ms-agrotrack-deliveries", "DEL-2026-000001",
                new Envelope.Actor("op-1", "Jefa de acopio", "OPERADOR"),
                Map.of("plantilla", plantilla, "productorId", "prod-1", "productoId", 7, "bodegaId", 3,
                        "cantidad", 100, "pesoRecibido", 98, "motivoRechazo", "Humedad"));
    }

    @Test
    @DisplayName("ProcesadosStore: la segunda vez que se ve un eventId responde false")
    void deduplica() {
        ProcesadosStore store = new ProcesadosStore();
        assertThat(store.marcarSiEsNuevo("e1")).isTrue();
        assertThat(store.marcarSiEsNuevo("e1")).isFalse();
        assertThat(store.marcarSiEsNuevo("e2")).isTrue();
        assertThat(store.tamano()).isEqualTo(2);
    }

    @Test
    @DisplayName("Ticket: se escribe un archivo de texto con los datos del lote")
    void ticket(@TempDir Path tmp) throws Exception {
        Path archivo = new TicketService(tmp.toString()).emitir(comando("receipt.ticket", "ticket-recepcion"));

        assertThat(archivo).exists().hasFileName("DEL-2026-000001.txt");
        String contenido = Files.readString(archivo);
        assertThat(contenido).contains("TICKET DE RECEPCION", "DEL-2026-000001", "Peso recibido: 98", "Jefa de acopio");
    }

    @Test
    @DisplayName("Voucher: se genera un PDF valido con el titulo segun la plantilla")
    void voucher(@TempDir Path tmp) throws Exception {
        Path archivo = new VoucherService(tmp.toString()).generar(comando("voucher.gen", "guia-despacho"));

        assertThat(archivo).exists().hasFileName("DEL-2026-000001.pdf");
        byte[] bytes = Files.readAllBytes(archivo);
        assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
        assertThat(bytes.length).isGreaterThan(1_000);
    }

    @Test
    @DisplayName("Email sin SMTP: no envia, no falla (queda en el log)")
    void emailSimulado() {
        JavaMailSender sender = mock(JavaMailSender.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);

        EmailService servicio = new EmailService(provider, "", "no-reply@agrotrack.local", "agrotrack.local");
        servicio.enviar(comando("email.send", "entrega-recibida"));

        verify(sender, never()).send((org.springframework.mail.SimpleMailMessage) org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Email con SMTP: envia al productor derivado del oid, con la plantilla correcta")
    void emailReal() {
        JavaMailSender sender = mock(JavaMailSender.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);

        EmailService servicio = new EmailService(provider, "smtp.ejemplo.cl", "no-reply@agrotrack.local", "agrotrack.local");
        servicio.enviar(comando("email.send", "entrega-rechazada"));

        var captor = org.mockito.ArgumentCaptor.forClass(org.springframework.mail.SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly("prod-1@agrotrack.local");
        assertThat(captor.getValue().getSubject()).isEqualTo("Tu lote DEL-2026-000001 fue rechazado");
        assertThat(captor.getValue().getText()).contains("Motivo: Humedad");
    }
}
