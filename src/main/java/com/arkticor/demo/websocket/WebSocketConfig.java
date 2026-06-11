package com.arkticor.demo.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Maps URL paths to WebSocket handlers. A WebSocket connection starts life as a normal HTTP GET
 * with an {@code Upgrade: websocket} header; these registrations tell Spring which handler owns
 * which path after the upgrade.
 */
@Configuration
@EnableWebSocket
@EnableScheduling // for the chat handler's heartbeat push
public class WebSocketConfig implements WebSocketConfigurer {

  private final EchoWebSocketHandler echoHandler;
  private final ChatWebSocketHandler chatHandler;

  public WebSocketConfig(EchoWebSocketHandler echoHandler, ChatWebSocketHandler chatHandler) {
    this.echoHandler = echoHandler;
    this.chatHandler = chatHandler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(echoHandler, "/ws/echo");
    registry.addHandler(chatHandler, "/ws/chat");
    // Browsers enforce the Origin header on WebSocket handshakes. Default = same origin only.
    // For cross-origin clients add: .setAllowedOrigins("https://your-frontend.example")
  }
}
