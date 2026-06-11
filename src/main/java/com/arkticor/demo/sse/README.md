# Server-Sent Events endpoints (Spring MVC `SseEmitter`)

One-way server -> client streaming over PLAIN HTTP. Two endpoints:
`/api/sse/countdown` (finite stream that completes) and
`/api/sse/notifications` (long-lived broadcast channel fed by a POST).
The one-way counterpart of the `websocket` feature — compare the two.

## Dependency

None. SSE ships with Spring MVC:

```groovy
implementation 'org.springframework.boot:spring-boot-starter-webmvc'
```

## Files

- `SseCountdownController.java` — minimal example: return an `SseEmitter`,
  push events from a worker thread, `complete()` when done. Start here.
- `NotificationBroadcaster.java` — the real-world pattern: emitter registry
  in a `CopyOnWriteArrayList`, cleanup via `onCompletion`/`onTimeout`/
  `onError`, event ids for `Last-Event-ID` reconnect, and a `@Scheduled`
  keep-alive comment every 15s.
- `NotificationController.java`  — `GET` subscribes (returns the emitter),
  `POST` publishes. Client -> server traffic is just normal HTTP.
- `static/sse-client.html`       — browser client using the native
  `EventSource` API (in `src/main/resources/static/`).

## How SSE works (the 60-second version)

1. Client sends a normal GET with `Accept: text/event-stream`.
2. Server responds `200 OK`, `Content-Type: text/event-stream` — and never
   finishes the response body. That ONE response is the channel.
3. Server appends UTF-8 text blocks to the body as things happen:

```
id: 42
event: notification
data: hello world

: this line is a comment (keep-alive), clients ignore it
```

   A blank line terminates each event. That's the whole wire format.
4. If the connection drops, the browser's `EventSource` reconnects BY
   ITSELF and sends `Last-Event-ID: 42` so the server can replay what was
   missed (this demo logs the id but does not buffer/replay).

So: plain HTTP, text-only, one direction, free auto-reconnect. No upgrade
handshake, no special proxy support, works through anything that can
stream HTTP.

## Try it

```bash
./gradlew bootRun
```

- Browser: open <http://localhost:8080/sse-client.html> in TWO tabs,
  subscribe in both, publish from one — both receive it. Kill the server
  and restart it: watch `EventSource` reconnect on its own (unlike the
  websocket client, which stays dead until you reconnect manually).
- CLI (`-N` disables curl's buffering):

```bash
curl -N localhost:8080/api/sse/countdown
curl -N localhost:8080/api/sse/notifications            # terminal 1
curl -X POST localhost:8080/api/sse/notifications \
     -H 'Content-Type: text/plain' -d 'hello'           # terminal 2
```

## Things to know before production

- **Emitters die in 3 ways** — completion, timeout, error — and ALL three
  callbacks must deregister the emitter or the registry leaks. This is the
  most common SSE bug.
- **Threading**: returning an `SseEmitter` frees the request thread, but
  every `send()` happens on whatever thread calls it. `send()` on a
  disconnected client throws `IOException` — always handle it.
- **Proxy idle timeouts**: send a comment (`:keep-alive`) more often than
  the shortest timeout in front of you (LB, nginx, gateway). Comments are
  invisible to `EventSource` handlers.
- **Reconnect & replay**: auto-reconnect is free, replay is NOT. To not
  lose events, buffer recent events server-side keyed by id and replay
  from the `Last-Event-ID` request header on resubscribe.
- **Scaling out**: same as websocket — the emitter list is per-JVM. Behind
  a load balancer, publish through Redis/Kafka pub-sub and let each
  instance relay to its local subscribers.
- **Connection limits**: over HTTP/1.1 browsers allow ~6 connections per
  host, and each SSE stream holds one PERMANENTLY. Use HTTP/2 (multiplexed
  streams) if a page needs several.

## SSE vs WebSocket vs polling

| Need                                         | Pick          |
|----------------------------------------------|---------------|
| Server -> client only (feeds, notifications, progress) | SSE — simpler, auto-reconnect free |
| Real two-way traffic (chat, games, collab editing)     | WebSocket (see `../websocket/`)    |
| Binary frames                                | WebSocket — SSE is text-only        |
| Rare updates, simplicity above all           | Plain polling                       |
| Works through strictest proxies/firewalls    | SSE or polling (plain HTTP)         |
