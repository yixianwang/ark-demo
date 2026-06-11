package com.arkticor.demo.sse;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * HTTP surface for the broadcast channel. SSE is one-way (server -> client), so the "client ->
 * server" direction is just a normal POST — this asymmetry is the main difference from WebSocket.
 */
@RestController
public class NotificationController {

  private final NotificationBroadcaster broadcaster;

  public NotificationController(NotificationBroadcaster broadcaster) {
    this.broadcaster = broadcaster;
  }

  // curl -N localhost:8080/api/sse/notifications
  @GetMapping(value = "/api/sse/notifications", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter subscribe() {
    return broadcaster.subscribe();
  }

  // curl -X POST localhost:8080/api/sse/notifications -H 'Content-Type: text/plain' -d 'hello'
  @PostMapping("/api/sse/notifications")
  public String publish(@RequestBody String message) {
    int delivered = broadcaster.publish(message);
    return "delivered to " + delivered + " subscriber(s)\n";
  }
}
