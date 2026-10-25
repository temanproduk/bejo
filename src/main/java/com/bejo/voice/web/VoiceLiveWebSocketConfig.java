package com.bejo.voice.web;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the live "say the sale" WebSocket. The upgrade request is an ordinary HTTP request, so it goes through the same
 * filters as every other call: {@code X-API-TOKEN} and the user's JWT are checked before the handler ever sees a connection.
 * No allowed origins are set, so a web page on another site cannot open it (the app sends no {@code Origin} header).
 */
@Configuration
@EnableWebSocket
public class VoiceLiveWebSocketConfig implements WebSocketConfigurer {

    /** Also known to {@code SecurityConfig}, whose 401-versus-404 decision consults request mappings, which a WebSocket is not. */
    public static final String PATH = "/api/voice/transaction/live";

    private final VoiceLiveWebSocketHandler handler;

    public VoiceLiveWebSocketConfig(VoiceLiveWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, PATH);
    }

    /**
     * The container's frame limits. The default (8 KB) is smaller than the first frame, which carries up to 200 products, and
     * than a second of audio; this is the first frame's worst case and 64 KB per audio frame (100 ms of 16 kHz PCM is 3.2 KB).
     *
     * <p>Set as Tomcat's context parameters, so it applies only where there is a Tomcat (a test with a mock servlet context has
     * none, and {@code ServletServerContainerFactoryBean} would fail it at start-up), and is picked up by the web server
     * whatever {@code spring.main.lazy-initialization} says. A connection's total length is bounded by {@code LiveSale}'s own
     * clocks, not by an idle timeout here.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> voiceLiveWebSocketLimits() {
        return factory -> factory.addContextCustomizers(context -> {
            context.addParameter("org.apache.tomcat.websocket.textBufferSize", String.valueOf(128 * 1024));
            context.addParameter("org.apache.tomcat.websocket.binaryBufferSize", String.valueOf(64 * 1024));
        });
    }
}
