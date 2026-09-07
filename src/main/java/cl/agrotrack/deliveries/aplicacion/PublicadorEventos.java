package cl.agrotrack.deliveries.aplicacion;

import cl.agrotrack.deliveries.dominio.Actor;
import cl.agrotrack.deliveries.dominio.Efecto;
import cl.agrotrack.deliveries.dominio.EstadoEntrega;
import cl.agrotrack.deliveries.infraestructura.persistencia.Entrega;

import java.util.Set;

/**
 * Salida del caso de uso hacia el mundo asincrono. El servicio solo dice
 * QUE paso y QUE efectos corresponden; quien lo traduce a Kafka (hechos) y
 * RabbitMQ (comandos) es la implementacion en infraestructura/mensajeria.
 *
 * <p>Contrato: las implementaciones publican DESPUES del commit de la
 * transaccion en curso. Publicar antes significaria anunciar algo que la
 * base todavia puede rechazar.
 */
public interface PublicadorEventos {

    /** T1: se creo una entrega. Solo hecho (Kafka), sin comandos. */
    void entregaRegistrada(Entrega entrega, Actor actor);

    /** T2..T8: cambio de estado con los efectos que dicto la maquina. */
    void entregaTransicionada(Entrega entrega, EstadoEntrega anterior, Set<Efecto> efectos, Actor actor);
}
