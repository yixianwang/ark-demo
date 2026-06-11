package com.arkticor.demo.sse;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The "hello world" of SSE: a finite stream that counts down from 10 and then completes.
 *
 * <p>Key concepts demonstrated:
 *
 * <ul>
 *   <li>{@link SseEmitter} = one long-lived HTTP response. Returning it from a controller method
 *       releases the request thread immediately (Spring MVC async support); the response stays open
 *       until {@code complete()} or {@code completeWithError()} is called.
 *   <li>Events are pushed from a DIFFERENT thread than the one that handled the request. Never
 *       block the controller method itself.
 *   <li>A finished stream ends with {@code complete()}, which closes the HTTP response. The
 *       browser's EventSource will auto-reconnect by default — for a one-shot stream like this the
 *       client should call {@code close()} when it receives the final event.
 * </ul>
 */
@RestController
public class SseCountdownController {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  // curl -N localhost:8080/api/sse/countdown
  @GetMapping("/api/sse/countdown")
  public SseEmitter countdown() {
    // 30s timeout: if we somehow never complete, the container aborts the response.
    SseEmitter emitter = new SseEmitter(30_000L);

    executor.execute(
        () -> {
          try {
            for (int i = 10; i >= 1; i--) {
              emitter.send(SseEmitter.event().name("tick").data(Integer.toString(i)));
              Thread.sleep(1_000);
            }
            emitter.send(SseEmitter.event().name("done").data("liftoff"));
            emitter.complete(); // closes the HTTP response normally
          } catch (IOException e) {
            // Client disconnected mid-stream; nothing left to write to.
            emitter.completeWithError(e);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(e);
          }
        });

    return emitter; // request thread is free as soon as we return
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }
}
