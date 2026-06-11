package com.arkticor.demo.websocket;

import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The "hello world" of WebSocket: every text frame the client sends comes straight back.
 *
 * <p>Key concepts demonstrated:
 *
 * <ul>
 *   <li>{@link WebSocketSession} = one open connection to one client. It stays alive until either
 *       side closes it — unlike HTTP, there is no request/response cycle.
 *   <li>The three lifecycle callbacks: connection opened, message received, connection closed.
 *   <li>{@code session.sendMessage(...)} pushes data to the client at ANY time, not just in
 *       response to a message. That server-push ability is the whole point of WebSocket.
 * </ul>
 */
@Component
public class EchoWebSocketHandler extends TextWebSocketHandler {

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws IOException {
    // Fires once per client, right after the HTTP -> WebSocket upgrade handshake completes.
    session.sendMessage(new TextMessage("echo: connected as " + session.getId()));
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message)
      throws IOException {
    session.sendMessage(new TextMessage("echo: " + message.getPayload()));
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    // status.getCode() 1000 = normal close, 1001 = browser tab closed / navigated away.
    // Nothing to clean up here; the chat handler shows real cleanup.
  }
}
