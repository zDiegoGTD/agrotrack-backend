package cl.agrotrack.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Configuración de timeouts HTTP para clientes REST del BFF.
 * Evita el agotamiento de hilos y ataques de denegación de servicio (Slowloris)
 * al asegurar que las conexiones y lecturas downstream corten antes de 11 segundos.
 */
@Configuration
public class HttpClientConfig {

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public HttpClientConfig(
            @Value("${agrotrack.http.client.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${agrotrack.http.client.read-timeout-ms:10000}") int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        // java.net.http y no HttpURLConnection: este ultimo rechaza PATCH, que usa
        // kafka-admin para cambiar la configuracion de un topico.
        HttpClient cliente = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(cliente);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return factory;
    }

    @Bean
    public RestClientCustomizer restClientCustomizer(ClientHttpRequestFactory clientHttpRequestFactory) {
        return builder -> builder.requestFactory(clientHttpRequestFactory);
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }
}
