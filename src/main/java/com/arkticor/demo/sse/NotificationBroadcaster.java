package com.arkticor.demo.sse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Long-lived broadcast channel: every published message is fanned out to all subscribed emitters.
 * This is the SSE counterpart of the websocket feature's ChatWebSocketHandler.
 *
 * <p>Key concepts demonstrated:
 *
 * <ul>
 *   <li>Emitter registry: this bean is a singleton shared by ALL subscribers, so emitters live in a
 *       {@link CopyOnWriteArrayList} (cheap iteration for broadcasts, rare mutation).
 *   <li>Cleanup callbacks: {@code onCompletion} / {@code onTimeout} / {@code onError} are the SSE
 *       equivalent of websocket's afterConnectionClosed — without them the list leaks dead
 *       emitters.
 *   <li>Event ids: each event carries a monotonically increasing id. On reconnect the browser
 *       automatically sends a {@code Last-Event-ID} header, which a real system would use to replay
 *       missed events from a buffer.
 *   <li>Heartbeat comments: a {@code :keep-alive} comment line every 15s stops proxies and load
 *       balancers from killing the idle connection, without triggering client message handlers.
 * </ul>
 */
@Component
@EnableScheduling
public class NotificationBroadcaster {

  private static final Logger log = LoggerFactory.getLogger(NotificationBroadcaster.class);

  /** No timeout: subscriptions stay open until the client disconnects. */
  private static final long NO_TIMEOUT = -1L;

  private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
  private final AtomicLong nextEventId = new AtomicLong(1);

  /** Creates, registers, and returns a new emitter for one subscriber. */
  public SseEmitter subscribe() {
    SseEmitter emitter = new SseEmitter(NO_TIMEOUT);

    // All three callbacks must deregister, or the list grows forever.
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(e -> emitters.remove(emitter));

    emitters.add(emitter);
    try {
      emitter.send(
          SseEmitter.event()
              .name("connected")
              .data("subscribed, " + emitters.size() + " client(s) online"));
    } catch (IOException e) {
      emitters.remove(emitter);
    }
    return emitter;
  }

  /** Fans one message out to every subscriber. Returns how many clients it reached. */
  public int publish(String message) {
    long id = nextEventId.getAndIncrement();
    int delivered = 0;
    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().id(Long.toString(id)).name("notification").data(message));
        delivered++;
      } catch (IOException e) {
        // Dead client: emitter's onError/onCompletion callback removes it from the list.
        log.debug("Dropping dead SSE subscriber: {}", e.getMessage());
      }
    }
    return delivered;
  }

  /**
   * SSE comment lines (starting with ':') are ignored by EventSource but keep the TCP connection
   * busy so idle-timeout proxies don't cut it.
   */
  @Scheduled(fixedRate = 15_000)
  void keepAlive() {
    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().comment("keep-alive"));
      } catch (IOException e) {
        log.debug("Keep-alive failed, subscriber gone: {}", e.getMessage());
      }
    }
  }

  public int subscriberCount() {
    return emitters.size();
  }
}
