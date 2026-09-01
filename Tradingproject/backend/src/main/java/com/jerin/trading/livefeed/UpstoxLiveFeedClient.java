package com.jerin.trading.livefeed;

import com.jerin.trading.broker.upstox.UpstoxProperties;
import com.jerin.trading.broker.upstox.UpstoxTokenStore;
import com.jerin.trading.livefeed.proto.Feed;
import com.jerin.trading.livefeed.proto.FeedResponse;
import com.jerin.trading.livefeed.proto.FullFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Connects to Upstox's live Market Data Feed (V3, binary protobuf — see
 * {@code src/main/proto/MarketDataFeed.proto}) as a WebSocket CLIENT, and re-broadcasts decoded
 * ticks to internal listeners. The mobile app never talks to Upstox directly — it connects to
 * {@link LiveFeedRelayHandler} instead, which subscribes here on its behalf. This keeps the
 * Upstox access token server-side only, same as every other broker call in this codebase.
 *
 * One shared upstream connection serves every subscriber, since this is a personal single-user
 * app — no per-user connection pooling needed.
 */
@Component
public class UpstoxLiveFeedClient extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(UpstoxLiveFeedClient.class);

    private final UpstoxProperties properties;
    private final UpstoxTokenStore tokenStore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StandardWebSocketClient webSocketClient = new StandardWebSocketClient();

    private final Map<String, Set<Consumer<Double>>> listenersByInstrumentKey = new ConcurrentHashMap<>();
    private volatile WebSocketSession session;

    public UpstoxLiveFeedClient(UpstoxProperties properties, UpstoxTokenStore tokenStore) {
        this.properties = properties;
        this.tokenStore = tokenStore;
    }

    /** @throws RuntimeException if the upstream connection can't be established (e.g. no Upstox
     * token yet) — propagated all the way to the mobile client as a clear error message, rather
     * than silently registering a subscription that will never actually produce ticks. */
    public synchronized void subscribe(String instrumentKey, Consumer<Double> onTick) {
        boolean firstListenerForThisKey = listenersByInstrumentKey
                .computeIfAbsent(instrumentKey, k -> ConcurrentHashMap.newKeySet())
                .add(onTick);
        ensureConnected();
        if (firstListenerForThisKey && isOpen()) {
            sendSubscriptionMessage("sub", List.of(instrumentKey));
        }
    }

    public synchronized void unsubscribe(String instrumentKey, Consumer<Double> onTick) {
        Set<Consumer<Double>> listeners = listenersByInstrumentKey.get(instrumentKey);
        if (listeners == null) {
            return;
        }
        listeners.remove(onTick);
        if (listeners.isEmpty()) {
            listenersByInstrumentKey.remove(instrumentKey);
            if (isOpen()) {
                sendSubscriptionMessage("unsub", List.of(instrumentKey));
            }
        }
    }

    private boolean isOpen() {
        WebSocketSession current = session;
        return current != null && current.isOpen();
    }

    /** Throws on failure — callers on the interactive (subscribe) path need to know so they can
     * tell the client; {@link #checkConnectionHealth()} (a background retry) catches it itself. */
    private synchronized void ensureConnected() {
        if (isOpen()) {
            return;
        }
        try {
            String wssUrl = authorize();
            log.info("Connecting to Upstox live feed...");
            session = webSocketClient.execute(this, wssUrl).get();
        } catch (Exception e) {
            log.error("Failed to connect to Upstox live feed", e);
            throw new IllegalStateException("Failed to connect to Upstox live feed: " + e.getMessage(), e);
        }
    }

    /** GET .../v3/feed/market-data-feed/authorize -> a single-use, pre-authenticated WSS URL. */
    private String authorize() {
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("Accept", "application/json")
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokenStore.getOrThrow());
                    return execution.execute(request, body);
                })
                .build();

        JsonNode response = restClient.get()
                .uri("/v3/feed/market-data-feed/authorize")
                .retrieve()
                .body(JsonNode.class);

        JsonNode data = response.get("data");
        JsonNode uriNode = data.has("authorized_redirect_uri") ? data.get("authorized_redirect_uri") : data.get("authorizedRedirectUri");
        if (uriNode == null) {
            throw new IllegalStateException("Upstox authorize response had no redirect URI: " + response);
        }
        return uriNode.asText();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // This callback fires synchronously during the handshake, BEFORE ensureConnected()'s
        // `this.session = webSocketClient.execute(...).get()` assignment completes — so the field
        // is still null at this point. Must assign from the parameter directly, not rely on the
        // field, or sendSubscriptionMessage()'s use of the field below throws a NullPointerException.
        this.session = session;
        log.info("Upstox live feed connected");
        // Re-subscribe to everything currently wanted (covers both first connect and reconnect).
        Set<String> keys = listenersByInstrumentKey.keySet();
        if (!keys.isEmpty()) {
            sendSubscriptionMessage("sub", List.copyOf(keys));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("Upstox live feed disconnected: {}", status);
        this.session = null;
        // Reconnected lazily: either the next subscribe() call, or checkConnectionHealth() below
        // if there are still active listeners waiting on data.
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Upstox live feed transport error", exception);
    }

    private void sendSubscriptionMessage(String method, List<String> instrumentKeys) {
        try {
            String guid = java.util.UUID.randomUUID().toString();
            Map<String, Object> message = Map.of(
                    "guid", guid,
                    "method", method,
                    "data", Map.of("mode", "ltpc", "instrumentKeys", instrumentKeys));
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (Exception e) {
            log.error("Failed to send {} message for {}", method, instrumentKeys, e);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        FeedResponse response = FeedResponse.parseFrom(message.getPayload());
        for (Map.Entry<String, Feed> entry : response.getFeedsMap().entrySet()) {
            Double ltp = extractLtp(entry.getValue());
            if (ltp == null) {
                continue;
            }
            Set<Consumer<Double>> listeners = listenersByInstrumentKey.get(entry.getKey());
            if (listeners != null) {
                for (Consumer<Double> listener : listeners) {
                    listener.accept(ltp);
                }
            }
        }
    }

    private Double extractLtp(Feed feed) {
        if (feed.hasLtpc()) {
            return feed.getLtpc().getLtp();
        }
        if (feed.hasFullFeed()) {
            FullFeed fullFeed = feed.getFullFeed();
            if (fullFeed.hasMarketFF()) {
                return fullFeed.getMarketFF().getLtpc().getLtp();
            }
            if (fullFeed.hasIndexFF()) {
                return fullFeed.getIndexFF().getLtpc().getLtp();
            }
        }
        if (feed.hasFirstLevelWithGreeks()) {
            return feed.getFirstLevelWithGreeks().getLtpc().getLtp();
        }
        return null;
    }

    /** Reconnects a dropped connection if there's still real demand for it — catches disconnects
     * that happen with no active subscribe() call to trigger a retry (e.g. Upstox closing the
     * socket at end of day). */
    @Scheduled(fixedDelay = 30_000)
    void checkConnectionHealth() {
        if (!listenersByInstrumentKey.isEmpty() && !isOpen()) {
            try {
                ensureConnected();
            } catch (Exception e) {
                // Already logged inside ensureConnected() — swallow here since this is a
                // background retry with no caller to report back to; it'll try again in 30s.
            }
        }
    }
}
