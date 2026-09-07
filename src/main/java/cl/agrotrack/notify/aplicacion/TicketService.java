package cl.agrotrack.notify.aplicacion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

/**
 * Comando {@code receipt.ticket}: ticket de recepcion y pesaje para bodega.
 * Se materializa como archivo de texto (lo que imprimiria la balanza) en
 * {@code <salida-dir>/tickets/<codigo>.txt}.
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final Path dir;

    public TicketService(@Value("${agrotrack.notify.salida-dir}") String salidaDir) {
        this.dir = Path.of(salidaDir, "tickets");
    }

    public Path emitir(Envelope cmd) {
        String contenido = """
                ============ AGROTRACK - TICKET DE RECEPCION ============
                Entrega      : %s
                Productor    : %s
                Producto     : %s
                Bodega       : %s
                Cant. decl.  : %s
                Peso recibido: %s
                Fecha        : %s
                Recibio      : %s (%s)
                Evento       : %s
                =========================================================
                """.formatted(
                cmd.subject(), cmd.dato("productorId"), cmd.dato("productoId"), cmd.dato("bodegaId"),
                cmd.dato("cantidad"), cmd.dato("pesoRecibido"),
                DateTimeFormatter.ISO_INSTANT.format(cmd.occurredAt()),
                cmd.actor() != null ? cmd.actor().nombre() : "-", cmd.actor() != null ? cmd.actor().userId() : "-",
                cmd.eventId());
        try {
            Files.createDirectories(dir);
            Path archivo = dir.resolve(cmd.subject() + ".txt");
            Files.writeString(archivo, contenido, StandardCharsets.UTF_8);
            log.info("[ticket] {} -> {}", cmd.subject(), archivo.toAbsolutePath());
            return archivo;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir el ticket de " + cmd.subject(), e);
        }
    }
}
