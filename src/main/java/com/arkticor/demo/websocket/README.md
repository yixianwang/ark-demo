# Raw WebSocket endpoints (Spring WebSocket, no STOMP)

Two endpoints built on Spring's low-level `TextWebSocketHandler` API:
`/ws/echo` (request/response on one connection) and `/ws/chat`
(broadcast fan-out + scheduled server push). Deliberately no STOMP/SockJS —
learn the raw protocol first, add abstractions later.

## Dependency

```groovy
implementation 'org.springframework.boot:spring-boot-starter-websocket'
```

## Files

- `WebSocketConfig.java`       — maps `/ws/echo` and `/ws/chat` to their
  handlers; the place to configure allowed origins and interceptors.
- `EchoWebSocketHandler.java`  — minimal handler: lifecycle callbacks +
  echo every frame back. Start reading here.
- `ChatWebSocketHandler.java`  — session registry in a `ConcurrentHashMap`,
  thread-safe broadcast via `ConcurrentWebSocketSessionDecorator`, and a
  `@Scheduled` heartbeat proving the server can push without being asked.
- `static/ws-client.html`      — zero-dependency browser client (in
  `src/main/resources/static/`).

## How WebSocket works (the 60-second version)

1. Client sends a normal HTTP GET with `Upgrade: websocket` +
   `Sec-WebSocket-Key` headers.
2. Server replies `101 Switching Protocols`. The TCP connection is now a
   full-duplex message pipe — HTTP is out of the picture.
3. Either side sends frames (text or binary) at any time. There is no
   request/response pairing; correlation is your application's job.
4. Either side sends a close frame (with a status code, 1000 = normal);
   the other side confirms and TCP shuts down.

So: ONE connection per client that stays open, server can talk first,
and you design your own message protocol on top.

## Try it

```bash
./gradlew bootRun
```

- Browser: open <http://localhost:8080/ws-client.html> in TWO tabs,
  connect both to `/ws/chat`, send messages, watch the fan-out. Wait 30s
  for a heartbeat.
- CLI:

```bash
# npm i -g wscat
wscat -c ws://localhost:8080/ws/echo
wscat -c ws://localhost:8080/ws/chat
```

## Things to know before production

- **Thread safety**: a raw `WebSocketSession` must never receive concurrent
  `sendMessage()` calls. Wrap it in `ConcurrentWebSocketSessionDecorator`
  (as `ChatWebSocketHandler` does) — it queues writes and closes clients
  that are too slow to drain them (backpressure).
- **Security**: the handshake is plain HTTP, so the usual auth applies
  (cookie/session or token in the handshake request). Origin checks matter:
  default is same-origin; open up deliberately with `setAllowedOrigins`.
- **Scaling out**: the session map lives in THIS JVM's memory. With 2+
  instances behind a load balancer, clients on instance A never see
  broadcasts from instance B. Standard fix: pub/sub backbone
  (Redis/Kafka/RabbitMQ) — each instance subscribes and relays to its own
  local sessions.
- **Proxies/timeouts**: idle connections get killed by LBs and gateways
  (often at 60s). Keep heartbeats shorter than the idle timeout. Protocol
  ping/pong frames exist too (`session.sendMessage(new PingMessage())`).
- **Message size**: containers cap frame/message size (Tomcat default 8KB
  text). Configure via `ServletServerContainerFactoryBean` if you need more.

## When you outgrow raw handlers

| Need                                        | Reach for                          |
|---------------------------------------------|------------------------------------|
| Topics, subscriptions, per-user queues      | STOMP over WebSocket (`@EnableWebSocketMessageBroker`) |
| Very old browsers / strict proxies          | SockJS fallback (`.withSockJS()`)  |
| Server -> client only, auto-reconnect free  | SSE (`SseEmitter`) — plain HTTP    |
| Structured payloads                         | JSON-encode your own message envelope (`{type, payload}`) |
