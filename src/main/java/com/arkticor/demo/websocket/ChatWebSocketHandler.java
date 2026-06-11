package com.arkticor.demo.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Broadcast chat room: every message from one client is fanned out to all connected clients. Also
 * pushes a server-initiated heartbeat every 30s to show that the server can talk first.
 *
 * <p>Key concepts demonstrated:
 *
 * <ul>
 *   <li>Session registry: the handler is a singleton shared by ALL clients, so per-client state
 *       must live in a concurrent map keyed by session id.
 *   <li>{@link ConcurrentWebSocketSessionDecorator}: raw sessions are NOT safe for concurrent
 *       sends. The decorator serializes writes and drops slow clients instead of blocking the whole
 *       broadcast (sendTimeLimit / bufferSizeLimit).
 *   <li>Server push via {@link Scheduled @Scheduled}: messages flow without any client request.
 * </ul>
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

  private static final int SEND_TIME_LIMIT_MS = 5_000;
  private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

  /** session id -> decorated session. The decorator makes sendMessage() thread-safe. */
  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    sessions.put(
        session.getId(),
        new ConcurrentWebSocketSessionDecorator(
            session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES));
    broadcast("[server] " + session.getId() + " joined (" + sessions.size() + " online)");
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    broadcast("[" + session.getId() + "] " + message.getPayload());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    // Always remove the session, otherwise the map leaks dead connections.
    sessions.remove(session.getId());
    broadcast("[server] " + session.getId() + " left (" + sessions.size() + " online)");
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    // Network-level failure (e.g. client vanished without a close frame). Container will
    // call afterConnectionClosed next, which removes the session from the registry.
    log.warn("Transport error on session {}: {}", session.getId(), exception.getMessage());
  }

  /** Server-initiated push: proves data can flow without any client asking for it. */
  @Scheduled(fixedRate = 30_000)
  void heartbeat() {
    if (!sessions.isEmpty()) {
      broadcast("[server] heartbeat, " + sessions.size() + " client(s) online");
    }
  }

  private void broadcast(String payload) {
    TextMessage message = new TextMessage(payload);
    for (WebSocketSession session : sessions.values()) {
      try {
        if (session.isOpen()) {
          session.sendMessage(message);
        }
      } catch (IOException e) {
        // One broken client must not break the broadcast for everyone else.
        log.warn("Failed to send to session {}: {}", session.getId(), e.getMessage());
      }
    }
  }
}
