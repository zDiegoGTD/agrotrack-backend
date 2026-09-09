package cl.agrotrack.deliveries.aplicacion;

import cl.agrotrack.deliveries.aplicacion.Dtos.CambioEstadoRequest;
import cl.agrotrack.deliveries.aplicacion.Dtos.EntregaResponse;
import cl.agrotrack.deliveries.aplicacion.Dtos.RegistrarEntregaRequest;
import cl.agrotrack.deliveries.aplicacion.Dtos.TransicionesResponse;
import cl.agrotrack.deliveries.aplicacion.Excepciones.AccesoDenegado;
import cl.agrotrack.deliveries.aplicacion.Excepciones.RecursoNoEncontrado;
import cl.agrotrack.deliveries.aplicacion.Excepciones.TransicionNoPermitida;
import cl.agrotrack.deliveries.dominio.Actor;
import cl.agrotrack.deliveries.dominio.Efecto;
import cl.agrotrack.deliveries.dominio.EstadoEntrega;
import cl.agrotrack.deliveries.dominio.MaquinaEstadosEntrega;
import cl.agrotrack.deliveries.dominio.ResultadoTransicion;
import cl.agrotrack.deliveries.dominio.Rol;
import cl.agrotrack.deliveries.infraestructura.persistencia.Entrega;
import cl.agrotrack.deliveries.infraestructura.persistencia.EntregaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Casos de uso de la entrega. Orquesta: maquina de estados (dominio puro),
 * catalog (capacidad), persistencia y publicacion de eventos.
 */
@Service
public class EntregaService {

    private static final Logger log = LoggerFactory.getLogger(EntregaService.class);

    private final EntregaRepository entregas;
    private final CatalogClient catalog;
    private final PublicadorEventos publicador;
    private final Clock clock;

    public EntregaService(EntregaRepository entregas, CatalogClient catalog, PublicadorEventos publicador, Clock clock) {
        this.entregas = entregas;
        this.catalog = catalog;
        this.publicador = publicador;
        this.clock = clock;
    }

    // ---- T1 ----

    @Transactional
    public EntregaResponse registrar(RegistrarEntregaRequest req, Actor actor) {
        if (!MaquinaEstadosEntrega.puedeRegistrar(actor.rol())) {
            throw new AccesoDenegado("Su rol no puede registrar entregas");
        }
        catalog.verificarProducto(req.productoId());
        catalog.verificarBodega(req.bodegaId());

        Entrega e = new Entrega(generarCodigo(), actor.userId(), req.productoId(), req.bodegaId(), req.cantidad());
        e = entregas.save(e);
        log.info("Entrega {} registrada por {} ({})", e.getCodigo(), actor.userId(), actor.rol());
        publicador.entregaRegistrada(e, actor);
        return EntregaResponse.de(e);
    }

    // ---- T2..T8 ----

    @Transactional
    public EntregaResponse cambiarEstado(Long id, CambioEstadoRequest req, Actor actor) {
        Entrega e = entrega(id);
        EstadoEntrega anterior = e.getEstado();
        EstadoEntrega destino = EstadoEntrega.parse(req.status());

        ResultadoTransicion r = MaquinaEstadosEntrega.evaluar(anterior, destino, actor.rol());
        if (!r.permitida()) {
            throw new TransicionNoPermitida(r.motivo(), anterior, destino);
        }
        if (destino == EstadoEntrega.RECHAZADA && (req.motivo() == null || req.motivo().isBlank())) {
            throw new IllegalArgumentException("Rechazar una entrega exige un motivo");
        }

        // Los efectos sobre catalog van ANTES de persistir: si la bodega no
        // tiene espacio, catalog responde 409, la excepcion sube y la
        // transaccion no llega a tocar la entrega.
        Set<Efecto> efectos = r.efectos();
        if (efectos.contains(Efecto.DESCONTAR_CAPACIDAD)) {
            // Se reserva el peso real si el operador lo informo; si no, lo declarado.
            BigDecimal aReservar = req.pesoRecibido() != null ? req.pesoRecibido() : e.getCantidad();
            catalog.reservarCapacidad(e.getBodegaId(), aReservar, e.getCodigo());
        }
        if (efectos.contains(Efecto.DEVOLVER_CAPACIDAD)) {
            catalog.liberarCapacidad(e.getBodegaId(), e.cantidadEnBodega(), e.getCodigo());
        }

        e.transicionar(destino, req.pesoRecibido(), req.motivo(), Instant.now(clock));
        e = entregas.save(e);
        log.info("Entrega {}: {} -> {} por {} ({}); efectos {}",
                e.getCodigo(), anterior, destino, actor.userId(), actor.rol(), efectos);
        publicador.entregaTransicionada(e, anterior, efectos, actor);
        return EntregaResponse.de(e);
    }

    // ---- consultas ----

    @Transactional(readOnly = true)
    public EntregaResponse obtener(Long id, Actor actor) {
        Entrega e = entrega(id);
        exigirVisibilidad(e, actor);
        return EntregaResponse.de(e);
    }

    @Transactional(readOnly = true)
    public List<EntregaResponse> listar(EstadoEntrega estado, Instant desde, Instant hasta, Actor actor) {
        // El productor solo ve lo suyo, diga lo que diga el filtro.
        String productorId = actor.rol() == Rol.CLIENTE ? actor.userId() : null;
        return entregas.findAll(EntregaRepository.filtro(estado, desde, hasta, productorId))
                .stream().map(EntregaResponse::de).toList();
    }

    @Transactional(readOnly = true)
    public TransicionesResponse transicionesDisponibles(Long id, Actor actor) {
        Entrega e = entrega(id);
        exigirVisibilidad(e, actor);
        List<EstadoEntrega> permitidas = Arrays.stream(EstadoEntrega.values())
                .filter(destino -> MaquinaEstadosEntrega.evaluar(e.getEstado(), destino, actor.rol()).permitida())
                .toList();
        return new TransicionesResponse(e.getEstado(), permitidas);
    }

    // ---- privados ----

    private Entrega entrega(Long id) {
        return entregas.findById(id).orElseThrow(() -> new RecursoNoEncontrado("Entrega", id));
    }

    private static void exigirVisibilidad(Entrega e, Actor actor) {
        if (actor.rol() == Rol.CLIENTE && !e.getProductorId().equals(actor.userId())) {
            throw new AccesoDenegado("La entrega no pertenece a este productor");
        }
    }

    private String generarCodigo() {
        return String.format("DEL-%d-%06d", Year.now(clock).getValue(), entregas.siguienteNumeroDeCodigo());
    }
}
