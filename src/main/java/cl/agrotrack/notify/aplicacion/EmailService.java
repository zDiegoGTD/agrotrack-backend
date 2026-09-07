package cl.agrotrack.notify.aplicacion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Comando {@code email.send}. Con SMTP configurado envia; sin SMTP escribe
 * el correo completo en el log, que para desarrollo es lo que se quiere ver.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    /** Asunto y cuerpo por plantilla (data.plantilla del comando). */
    private static final Map<String, String[]> PLANTILLAS = Map.of(
            "entrega-recibida", new String[]{"Tu lote %s fue recibido",
                    "Hola %s,\n\nTu entrega %s fue recibida en bodega. Peso registrado: %s.\n\nAgroTrack"},
            "entrega-en_clasificacion", new String[]{"Tu lote %s esta en clasificacion",
                    "Hola %s,\n\nTu entrega %s paso a clasificacion.\n\nAgroTrack"},
            "entrega-despachada", new String[]{"Tu lote %s fue despachado",
                    "Hola %s,\n\nTu entrega %s salio a planta. La guia de despacho queda disponible en el portal.\n\nAgroTrack"},
            "entrega-rechazada", new String[]{"Tu lote %s fue rechazado",
                    "Hola %s,\n\nTu entrega %s fue rechazada. Motivo: %s.\n\nAgroTrack"});

    private final JavaMailSender mailSender;
    private final boolean smtpConfigurado;
    private final String remitente;
    private final String dominioProductores;

    public EmailService(ObjectProvider<JavaMailSender> mailSender,
                        @Value("${spring.mail.host:}") String smtpHost,
                        @Value("${agrotrack.notify.remitente}") String remitente,
                        @Value("${agrotrack.notify.dominio-productores}") String dominioProductores) {
        this.mailSender = mailSender.getIfAvailable();
        this.smtpConfigurado = smtpHost != null && !smtpHost.isBlank() && this.mailSender != null;
        this.remitente = remitente;
        this.dominioProductores = dominioProductores;
    }

    public void enviar(Envelope cmd) {
        String plantilla = cmd.dato("plantilla");
        String[] textos = PLANTILLAS.getOrDefault(plantilla, new String[]{
                "Novedad en tu entrega %s", "Hola %s,\n\nTu entrega %s cambio de estado.\n\nAgroTrack"});

        String codigo = cmd.subject();
        String productor = cmd.dato("productorId");
        String extra = "entrega-rechazada".equals(plantilla)
                ? String.valueOf(cmd.dato("motivoRechazo"))
                : String.valueOf(cmd.dato("pesoRecibido") != null ? cmd.dato("pesoRecibido") : cmd.dato("cantidad"));

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(remitente);
        mail.setTo(destinatario(cmd));
        mail.setSubject(String.format(textos[0], codigo));
        mail.setText(String.format(textos[1], productor, codigo, extra));

        if (smtpConfigurado) {
            mailSender.send(mail);
            log.info("[email] enviado a {} asunto='{}' eventId={}", mail.getTo()[0], mail.getSubject(), cmd.eventId());
        } else {
            log.info("[email simulado] para={} asunto='{}' eventId={}\n{}",
                    mail.getTo()[0], mail.getSubject(), cmd.eventId(), mail.getText());
        }
    }

    /** Si el comando trae email explicito se usa; si no, se deriva del id del productor. */
    String destinatario(Envelope cmd) {
        String email = cmd.dato("email");
        if (email != null && email.contains("@")) {
            return email;
        }
        return cmd.dato("productorId") + "@" + dominioProductores;
    }
}
