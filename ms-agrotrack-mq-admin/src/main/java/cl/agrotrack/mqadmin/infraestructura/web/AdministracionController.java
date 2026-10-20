package cl.agrotrack.mqadmin.infraestructura.web;

import cl.agrotrack.mqadmin.aplicacion.RabbitAdminService;
import cl.agrotrack.mqadmin.aplicacion.RabbitAdminService.EstadoCola;
import cl.agrotrack.mqadmin.infraestructura.web.dto.AdminDtos;
import cl.agrotrack.mqadmin.infraestructura.web.dto.AdminDtos.BindingRequest;
import cl.agrotrack.mqadmin.infraestructura.web.dto.AdminDtos.CrearColaRequest;
import cl.agrotrack.mqadmin.infraestructura.web.dto.AdminDtos.CrearExchangeRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Set;

/**
 * API REST para administrar la topologia de RabbitMQ: colas, exchanges y
 * bindings. Solo ADMIN: crear o borrar una cola en caliente cambia por donde
 * viajan los mensajes de todo el sistema.
 *
 * <pre>
 * POST   /api/mq/queues                 crear cola (y su DLQ)
 * GET    /api/mq/queues/{nombre}        estado de una cola
 * DELETE /api/mq/queues/{nombre}        eliminar cola
 * DELETE /api/mq/queues/{nombre}/mensajes  purgar (vaciar sin borrar)
 * POST   /api/mq/exchanges              crear exchange (direct, topic, fanout)
 * DELETE /api/mq/exchanges/{nombre}     eliminar exchange
 * POST   /api/mq/bindings               unir cola y exchange
 * DELETE /api/mq/bindings               deshacer la union (mismo cuerpo)
 * GET    /api/mq/protegidos             recursos del sistema que la API no toca
 * </pre>
 *
 * <p>No hay logica de RabbitMQ aqui: todo pasa por {@link RabbitAdminService}.
 */
@RestController
@RequestMapping("/api/mq")
@Validated
@PreAuthorize("hasRole('ADMIN')")
public class AdministracionController {

    private final RabbitAdminService servicio;

    public AdministracionController(RabbitAdminService servicio) {
        this.servicio = servicio;
    }

    // ---- colas ----

    @PostMapping("/queues")
    public ResponseEntity<EstadoCola> crearCola(@Valid @RequestBody CrearColaRequest req) {
        EstadoCola creada = servicio.crearCola(req.nombre(), req.esDurable(), req.llevaDlq());
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{nombre}").buildAndExpand(req.nombre()).toUri();
        return ResponseEntity.created(ubicacion).body(creada);
    }

    @GetMapping("/queues/{nombre}")
    public EstadoCola estadoCola(@PathVariable @Pattern(regexp = AdminDtos.NOMBRE, message = AdminDtos.NOMBRE_MSG) String nombre) {
        return servicio.estado(nombre);
    }

    @DeleteMapping("/queues/{nombre}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminarCola(@PathVariable @Pattern(regexp = AdminDtos.NOMBRE, message = AdminDtos.NOMBRE_MSG) String nombre) {
        servicio.eliminarCola(nombre);
    }

    @DeleteMapping("/queues/{nombre}/mensajes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purgarCola(@PathVariable @Pattern(regexp = AdminDtos.NOMBRE, message = AdminDtos.NOMBRE_MSG) String nombre) {
        servicio.purgarCola(nombre);
    }

    // ---- exchanges ----

    @PostMapping("/exchanges")
    @ResponseStatus(HttpStatus.CREATED)
    public void crearExchange(@Valid @RequestBody CrearExchangeRequest req) {
        servicio.crearExchange(req.nombre(), req.tipo(), req.esDurable());
    }

    @DeleteMapping("/exchanges/{nombre}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminarExchange(@PathVariable @Pattern(regexp = AdminDtos.NOMBRE, message = AdminDtos.NOMBRE_MSG) String nombre) {
        servicio.eliminarExchange(nombre);
    }

    // ---- bindings ----

    @PostMapping("/bindings")
    @ResponseStatus(HttpStatus.CREATED)
    public void crearBinding(@Valid @RequestBody BindingRequest req) {
        servicio.crearBinding(req.cola(), req.exchange(), req.routingKey());
    }

    /** Un binding no tiene id propio en RabbitMQ: se identifica por sus tres partes, que van en el cuerpo. */
    @DeleteMapping("/bindings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminarBinding(@Valid @RequestBody BindingRequest req) {
        servicio.eliminarBinding(req.cola(), req.exchange(), req.routingKey());
    }

    // ---- consulta ----

    @GetMapping("/protegidos")
    public Set<String> protegidos() {
        return RabbitAdminService.recursosDelSistema();
    }
}
