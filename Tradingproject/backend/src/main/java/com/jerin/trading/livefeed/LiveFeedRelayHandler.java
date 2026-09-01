package com.jerin.trading.livefeed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The endpoint the mobile app actually connects to ({@code /ws/live-feed}) — plain JSON over
 * WebSocket, no protobuf/broker-token knowledge needed client-side. One instrument subscription
 * per connected session at a time (matches "viewing one stock's chart"); sending a new
 * {@code subscribe} message swaps it.
 *
 * Client -> server: {"action":"subscribe","instrument":"SBILIFE"} or {"action":"unsubscribe"}
 * Server -> client: {"instrument":"SBILIFE","ltp":1740.55,"ts":1735689600000}
 *                 or {"error":"..."} if the instrument can't be resolved to a real broker key
 */
@Component
public class LiveFeedRelayHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveFeedRelayHandler.class);

    private final UpstoxLiveFeedClient upstoxLiveFeedClient;
    private final InstrumentKeyResolver instrumentKeyResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Per-session active subscription, so a new subscribe/disconnect can cleanly unwind the old one. */
    private final Map<WebSocketSession, Subscription> activeSubscriptions = new ConcurrentHashMap<>();

    private record Subscription(String instrumentTag, String instrumentKey, Consumer<Double> listener) {
    }

    public LiveFeedRelayHandler(UpstoxLiveFeedClient upstoxLiveFeedClient, InstrumentKeyResolver instrumentKeyResolver) {
        this.upstoxLiveFeedClient = upstoxLiveFeedClient;
        this.instrumentKeyResolver = instrumentKeyResolver;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Never let an exception here propagate up to Spring's ExceptionWebSocketHandlerDecorator
        // — that closes the whole session with an opaque 1011, which is a poor experience for a
        // client that just needs to know e.g. "no Upstox token yet, log in" and keep listening.
        try {
            handleTextMessageInternal(session, message);
        } catch (Exception e) {
            log.error("Live feed subscribe failed for session {}", session.getId(), e);
            sendError(session, describeError(e));
        }
    }

    private void handleTextMessageInternal(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode json = objectMapper.readTree(message.getPayload());
        String action = json.path("action").asText();

        clearExistingSubscription(session);

        if ("unsubscribe".equals(action)) {
            return;
        }
        if (!"subscribe".equals(action)) {
            sendError(session, "Unknown action: " + action);
            return;
        }

        String instrumentTag = json.path("instrument").asText(null);
        if (instrumentTag == null || instrumentTag.isBlank()) {
            sendError(session, "Missing \"instrument\"");
            return;
        }

        Optional<String> instrumentKey = instrumentKeyResolver.resolve(instrumentTag);
        if (instrumentKey.isEmpty()) {
            sendError(session, "Unknown instrument: " + instrumentTag);
            return;
        }

        Consumer<Double> listener = ltp -> relayTick(session, instrumentTag, ltp);
        activeSubscriptions.put(session, new Subscription(instrumentTag, instrumentKey.get(), listener));
        upstoxLiveFeedClient.subscribe(instrumentKey.get(), listener);
        log.info("Session {} subscribed to {} ({})", session.getId(), instrumentTag, instrumentKey.get());
    }

    /** Surfaces the one error a client actually needs to act on distinctly; everything else stays generic. */
    private String describeError(Exception e) {
        if (e instanceof IllegalStateException && e.getMessage() != null && e.getMessage().contains("No Upstox access token")) {
            return "Backend isn't logged in to Upstox yet — live data unavailable until that happens.";
        }
        return "Failed to subscribe: " + e.getMessage();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clearExistingSubscription(session);
    }

    private void clearExistingSubscription(WebSocketSession session) {
        Subscription previous = activeSubscriptions.remove(session);
        if (previous != null) {
            upstoxLiveFeedClient.unsubscribe(previous.instrumentKey(), previous.listener());
        }
    }

    private void relayTick(WebSocketSession session, String instrumentTag, double ltp) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                    Map.of("instrument", instrumentTag, "ltp", ltp, "ts", System.currentTimeMillis()))));
        } catch (IOException e) {
            log.warn("Failed to relay tick to session {}", session.getId(), e);
        }
    }

    private void sendError(WebSocketSession session, String message) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("error", message))));
        } catch (IOException ignored) {
        }
    }
}
