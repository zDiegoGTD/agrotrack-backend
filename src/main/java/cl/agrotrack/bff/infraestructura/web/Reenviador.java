package cl.agrotrack.bff.infraestructura.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.net.URI;

/** Manda una request al servicio de dominio y devuelve su respuesta tal cual. Interfaz para poder testear el controlador sin red. */
public interface Reenviador {

    ResponseEntity<byte[]> reenviar(HttpMethod metodo, URI destino, HttpHeaders cabeceras, byte[] cuerpo);
}
