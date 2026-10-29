package com.einsteinmods.opendoors.core.signaling;

import com.einsteinmods.opendoors.core.signaling.HubMessage.Inbound;
import com.einsteinmods.opendoors.core.signaling.HubMessage.Outbound;
import com.einsteinmods.opendoors.core.signaling.HubMessage.Welcome;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Keeps one authenticated WebSocket to the signaling hub, reconnecting with {@link Backoff} and
 * honouring Retry-After. Authentication (protocol in {@code backend/src/auth.ts} and {@code
 * certificate.ts}): get a stateless challenge id, let the {@link Authenticator} sign it with the
 * account's Mojang player certificate, then open the socket with the challenge id in the query and
 * the {@link CertificateProof} in the upgrade headers. Nothing is sent to Mojang.
 *
 * <p>All state lives on one daemon thread, parked between events; it also runs the HTTP client's
 * callbacks. Listener methods are called on that thread.
 */
public final class SignalingClient implements HubLink {
  /** Proves the signed-in account to the backend for one challenge id. */
  @FunctionalInterface
  public interface Authenticator {
    /**
     * @throws UnavailableException when this account has no player certificate (retried only at the
     *     longest backoff)
     */
    CertificateProof prove(String serverId) throws Exception;
  }

  /** The account cannot prove itself (no Mojang player certificate): online play stays off. */
  public static final class UnavailableException extends Exception {
    public UnavailableException(String message) {
      super(message, null, false, false);
    }
  }

  /** Where and how to open the socket. */
  private record Handshake(URI target, Map<String, String> headers) {}

  public interface Listener {
    void onWelcome(Welcome welcome);

    void onMessage(Inbound message);

    /** The socket is gone; {@code retrying} is false once the client stopped for good. */
    void onDisconnected(boolean retrying);
  }

  public enum State {
    STOPPED,
    CONNECTING,
    CONNECTED,
    WAITING,
    /** Another game signed in with the same account took over the socket. */
    REPLACED
  }

  private static final String THREAD_NAME = "OpenDoors Signaling";
  private static final String PING = "ping";
  private static final String PONG = "pong";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
  private static final long PING_INTERVAL_SECONDS = 30;
  private static final long SILENCE_LIMIT_MILLIS = 75_000;
  private static final int CLOSE_NORMAL = 1000;
  private static final int CLOSE_POLICY_VIOLATION = 1008;
  private static final int CLOSE_REPLACED = 4000;
  private static final int HTTP_OK = 200;
  private static final int HTTP_TOO_MANY_REQUESTS = 429;
  private static final String RETRY_AFTER = "Retry-After";
  private static final long MILLIS_PER_SECOND = 1_000;

  private final BackendEndpoint endpoint;
  private final String playerName;
  private final Authenticator authenticator;
  private final Listener listener;
  private final ScheduledThreadPoolExecutor executor;
  private final HttpClient http;

  // Owned by the executor thread.
  private State state = State.STOPPED;
  private int generation;
  private int attempt;
  private WebSocket socket;
  private CompletableFuture<WebSocket> sendChain;
  private ScheduledFuture<?> reconnectTask;
  private ScheduledFuture<?> pingTask;
  private long lastInboundMillis;
  private long sendPausedUntilMillis;
  private final StringBuilder partialText = new StringBuilder();

  /**
   * @param authenticator null for a dev identity (backend started with DEV_AUTH=1)
   */
  public SignalingClient(
      BackendEndpoint endpoint, String playerName, Authenticator authenticator, Listener listener) {
    if (!Validation.isName(playerName)) {
      throw new IllegalArgumentException("invalid player name");
    }
    this.endpoint = endpoint;
    this.playerName = playerName;
    this.authenticator = authenticator;
    this.listener = listener;
    this.executor =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, THREAD_NAME);
              thread.setDaemon(true);
              return thread;
            });
    this.executor.setRemoveOnCancelPolicy(true);
    this.http = HttpClient.newBuilder().executor(executor).connectTimeout(CONNECT_TIMEOUT).build();
  }

  /** Connects (idempotent); a stopped or replaced client starts over. */
  public void start() {
    executor.execute(
        () -> {
          if (state == State.STOPPED || state == State.REPLACED) {
            attempt = 0;
            connect();
          }
        });
  }

  /** Closes the socket and cancels every timer; the thread stays parked until {@link #start}. */
  public void stop() {
    executor.execute(() -> disconnect(State.STOPPED, CLOSE_NORMAL));
  }

  /** Stops and releases the thread and HTTP client for good. */
  public void close() {
    executor.execute(
        () -> {
          disconnect(State.STOPPED, CLOSE_NORMAL);
          http.shutdownNow();
          executor.shutdown();
        });
  }

  /**
   * Queues a message; it is dropped (false) when the socket is not connected or the hub asked to
   * slow down.
   */
  @Override
  public CompletableFuture<Boolean> send(Outbound message) {
    String text = HubCodec.encode(message);
    CompletableFuture<Boolean> result = new CompletableFuture<>();
    executor.execute(() -> result.complete(sendNow(text)));
    return result;
  }

  @Override
  public void execute(Runnable task) {
    executor.execute(task);
  }

  @Override
  public java.util.concurrent.Future<?> schedule(Runnable task, long delayMillis) {
    return executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
  }

  public State state() {
    return state;
  }

  private boolean sendNow(String text) {
    if (state != State.CONNECTED || System.currentTimeMillis() < sendPausedUntilMillis) {
      return false;
    }
    WebSocket current = socket;
    sendChain = sendChain.thenCompose(ignored -> current.sendText(text, true));
    return true;
  }

  private void connect() {
    cancelTimers();
    int connection = ++generation;
    state = State.CONNECTING;
    CompletableFuture<Handshake> handshake =
        authenticator == null
            ? CompletableFuture.completedFuture(
                new Handshake(endpoint.socket(playerName, null), Map.of()))
            : requestChallenge().thenApplyAsync(this::authenticate, executor);
    handshake
        .thenCompose(
            target -> {
              WebSocket.Builder builder =
                  http.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT);
              target.headers().forEach(builder::header);
              return builder.buildAsync(target.target(), new SocketListener(connection));
            })
        .whenCompleteAsync(
            (webSocket, error) -> {
              if (connection != generation) {
                if (webSocket != null) {
                  webSocket.abort();
                }
                return;
              }
              if (error != null) {
                if (unwrap(error) instanceof UnavailableException) {
                  // No certificate (yet): retry rarely, the key service may come back.
                  attempt = Integer.MAX_VALUE / 2;
                }
                scheduleReconnect(retryAfterMillis(error));
                return;
              }
              socket = webSocket;
              sendChain = CompletableFuture.completedFuture(webSocket);
              lastInboundMillis = System.currentTimeMillis();
              pingTask =
                  executor.scheduleWithFixedDelay(
                      this::ping, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
            },
            executor);
  }

  private CompletableFuture<String> requestChallenge() {
    HttpRequest request =
        HttpRequest.newBuilder(endpoint.challenge())
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(
            response -> {
              if (response.statusCode() == HTTP_TOO_MANY_REQUESTS) {
                throw new RetryLaterException(headerSeconds(response));
              }
              if (response.statusCode() != HTTP_OK) {
                throw new CompletionException(new IOException("challenge refused"));
              }
              return parseServerId(response.body());
            });
  }

  private Handshake authenticate(String serverId) {
    CertificateProof proof;
    try {
      proof = authenticator.prove(serverId);
    } catch (Exception exception) {
      throw new CompletionException(exception);
    }
    return new Handshake(endpoint.socket(playerName, serverId), proof.headers());
  }

  private static Throwable unwrap(Throwable error) {
    return error instanceof CompletionException && error.getCause() != null
        ? error.getCause()
        : error;
  }

  private static String parseServerId(String body) {
    try {
      JsonElement root = JsonParser.parseString(body);
      if (root.isJsonObject()
          && root.getAsJsonObject().get("serverId") instanceof JsonPrimitive primitive
          && primitive.isString()
          && Validation.isServerId(primitive.getAsString())) {
        return primitive.getAsString();
      }
    } catch (JsonParseException exception) {
      throw new CompletionException(new IOException("malformed challenge"));
    }
    throw new CompletionException(new IOException("malformed challenge"));
  }

  private void ping() {
    if (state != State.CONNECTED && state != State.CONNECTING) {
      return;
    }
    if (System.currentTimeMillis() - lastInboundMillis > SILENCE_LIMIT_MILLIS) {
      // The hub always answers pings; silence means a dead path (sleep, network switch).
      dropSocket();
      scheduleReconnect(0);
      return;
    }
    WebSocket current = socket;
    if (current != null) {
      sendChain = sendChain.thenCompose(ignored -> current.sendText(PING, true));
    }
  }

  private void onMessageText(int connection, String text) {
    if (connection != generation) {
      return;
    }
    lastInboundMillis = System.currentTimeMillis();
    if (PONG.equals(text)) {
      return;
    }
    Inbound message = HubCodec.decode(text);
    switch (message) {
      case null -> {
        // Not a valid hub message: ignore it, the hub never sends one.
      }
      case Welcome welcome -> {
        state = State.CONNECTED;
        attempt = 0;
        listener.onWelcome(welcome);
      }
      case HubMessage.ErrorMessage error when error.retryAfterSeconds() > 0 -> {
        sendPausedUntilMillis =
            System.currentTimeMillis() + error.retryAfterSeconds() * MILLIS_PER_SECOND;
        listener.onMessage(error);
      }
      default -> listener.onMessage(message);
    }
  }

  private void onClosed(int connection, int code) {
    if (connection != generation) {
      return;
    }
    if (code == CLOSE_REPLACED) {
      disconnect(State.REPLACED, CLOSE_NORMAL);
      return;
    }
    if (code == CLOSE_POLICY_VIOLATION) {
      attempt = Integer.MAX_VALUE / 2;
    }
    scheduleReconnect(0);
  }

  private void scheduleReconnect(long minimumMillis) {
    dropSocket();
    cancelTimers();
    state = State.WAITING;
    long delay = Backoff.delayMillis(attempt, minimumMillis, ThreadLocalRandom.current());
    attempt = Math.min(attempt + 1, Integer.MAX_VALUE / 2);
    int connection = ++generation;
    reconnectTask =
        executor.schedule(
            () -> {
              if (connection == generation && state == State.WAITING) {
                connect();
              }
            },
            delay,
            TimeUnit.MILLISECONDS);
    listener.onDisconnected(true);
  }

  private void disconnect(State finalState, int closeCode) {
    boolean wasActive = state != State.STOPPED && state != State.REPLACED;
    generation++;
    cancelTimers();
    WebSocket current = socket;
    socket = null;
    if (current != null) {
      current.sendClose(closeCode, "").exceptionally(ignored -> null);
    }
    state = finalState;
    if (wasActive) {
      listener.onDisconnected(false);
    }
  }

  private void dropSocket() {
    WebSocket current = socket;
    socket = null;
    if (current != null) {
      current.abort();
    }
  }

  private void cancelTimers() {
    if (reconnectTask != null) {
      reconnectTask.cancel(false);
      reconnectTask = null;
    }
    if (pingTask != null) {
      pingTask.cancel(false);
      pingTask = null;
    }
  }

  private static long retryAfterMillis(Throwable error) {
    Throwable cause =
        error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    if (cause instanceof RetryLaterException retry) {
      return retry.seconds * MILLIS_PER_SECOND;
    }
    if (cause instanceof WebSocketHandshakeException handshake
        && handshake.getResponse().statusCode() == HTTP_TOO_MANY_REQUESTS) {
      return headerSeconds(handshake.getResponse()) * MILLIS_PER_SECOND;
    }
    return 0;
  }

  private static long headerSeconds(HttpResponse<?> response) {
    return response.headers().firstValue(RETRY_AFTER).map(SignalingClient::parseSeconds).orElse(0L);
  }

  private static long parseSeconds(String value) {
    try {
      return Math.clamp(Long.parseLong(value.strip()), 0, Validation.MAX_RETRY_AFTER_SECONDS);
    } catch (NumberFormatException exception) {
      return 0;
    }
  }

  /** A 429 from the challenge endpoint, carrying its Retry-After. */
  private static final class RetryLaterException extends RuntimeException {
    private final long seconds;

    RetryLaterException(long seconds) {
      super(null, null, false, false);
      this.seconds = seconds;
    }
  }

  /** One per socket; stale sockets are ignored through {@code connection}. */
  private final class SocketListener implements WebSocket.Listener {
    private final int connection;

    SocketListener(int connection) {
      this.connection = connection;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      if (partialText.length() + data.length() > Validation.MAX_MESSAGE_CHARS) {
        partialText.setLength(0);
        webSocket.abort();
        onClosed(connection, CLOSE_POLICY_VIOLATION);
        return null;
      }
      partialText.append(data);
      if (last) {
        String text = partialText.toString();
        partialText.setLength(0);
        onMessageText(connection, text);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      onClosed(connection, statusCode);
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      onClosed(connection, CLOSE_NORMAL);
    }
  }
}
