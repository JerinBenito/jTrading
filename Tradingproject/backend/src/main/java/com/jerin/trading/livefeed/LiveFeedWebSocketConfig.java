package com.jerin.trading.livefeed;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers {@link LiveFeedRelayHandler} at /ws/live-feed — no auth, matches the rest of this
 * personal single-user backend (no Spring Security anywhere in this app). */
@Configuration
@EnableWebSocket
public class LiveFeedWebSocketConfig implements WebSocketConfigurer {

    private final LiveFeedRelayHandler relayHandler;

    public LiveFeedWebSocketConfig(LiveFeedRelayHandler relayHandler) {
        this.relayHandler = relayHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(relayHandler, "/ws/live-feed").setAllowedOrigins("*");
    }
}
