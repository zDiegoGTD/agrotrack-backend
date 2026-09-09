package cl.agrotrack.notify.aplicacion;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

/**
 * Comando {@code voucher.gen}: la guia de despacho (o comprobante de acopio)
 * en PDF, en {@code <salida-dir>/vouchers/<codigo>.pdf}.
 */
@Service
public class VoucherService {

    private static final Logger log = LoggerFactory.getLogger(VoucherService.class);

    private final Path dir;

    public VoucherService(@Value("${agrotrack.notify.salida-dir}") String salidaDir) {
        this.dir = Path.of(salidaDir, "vouchers");
    }

    public Path generar(Envelope cmd) {
        String titulo = "guia-despacho".equals(cmd.dato("plantilla"))
                ? "GUIA DE DESPACHO" : "COMPROBANTE DE ACOPIO";
        try {
            Files.createDirectories(dir);
            Path archivo = dir.resolve(cmd.subject() + ".pdf");
            try (OutputStream out = Files.newOutputStream(archivo)) {
                escribirPdf(out, titulo, cmd);
            }
            log.info("[voucher] {} {} -> {}", titulo, cmd.subject(), archivo.toAbsolutePath());
            return archivo;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el PDF de " + cmd.subject(), e);
        }
    }

    static void escribirPdf(OutputStream out, String titulo, Envelope cmd) {
        Document doc = new Document();
        PdfWriter.getInstance(doc, out);
        doc.open();

        Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 11);

        Paragraph cab = new Paragraph("AgroTrack - " + titulo, h1);
        cab.setAlignment(Element.ALIGN_CENTER);
        cab.setSpacingAfter(16);
        doc.add(cab);

        PdfPTable tabla = new PdfPTable(2);
        tabla.setWidthPercentage(100);
        fila(tabla, normal, "Entrega", cmd.subject());
        fila(tabla, normal, "Productor", cmd.dato("productorId"));
        fila(tabla, normal, "Producto", cmd.dato("productoId"));
        fila(tabla, normal, "Bodega", cmd.dato("bodegaId"));
        fila(tabla, normal, "Cantidad declarada", cmd.dato("cantidad"));
        fila(tabla, normal, "Peso recibido", cmd.dato("pesoRecibido"));
        fila(tabla, normal, "Fecha recepcion", cmd.dato("fechaRecepcion"));
        fila(tabla, normal, "Fecha despacho", cmd.dato("fechaDespacho"));
        fila(tabla, normal, "Emitido", DateTimeFormatter.ISO_INSTANT.format(cmd.occurredAt()));
        fila(tabla, normal, "Autorizo", cmd.actor() != null ? cmd.actor().nombre() : "-");
        fila(tabla, normal, "Evento", cmd.eventId());
        doc.add(tabla);

        Paragraph pie = new Paragraph("Documento generado automaticamente. Trace " + cmd.traceId(),
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8));
        pie.setSpacingBefore(24);
        doc.add(pie);
        doc.close();
    }

    private static void fila(PdfPTable t, Font f, String k, String v) {
        PdfPCell c1 = new PdfPCell(new Phrase(k, f));
        PdfPCell c2 = new PdfPCell(new Phrase(v == null ? "-" : v, f));
        c1.setPadding(6);
        c2.setPadding(6);
        t.addCell(c1);
        t.addCell(c2);
    }
}
