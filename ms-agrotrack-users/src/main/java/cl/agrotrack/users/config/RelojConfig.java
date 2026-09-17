package cl.agrotrack.users.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Reloj inyectable: las fechas de ingreso y aprobacion no dependen de Instant.now() suelto. */
@Configuration
public class RelojConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
