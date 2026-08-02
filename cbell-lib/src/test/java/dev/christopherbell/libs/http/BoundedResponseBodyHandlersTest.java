package dev.christopherbell.libs.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BoundedResponseBodyHandlersTest {
  @Test
  void acceptsAResponseAtTheExactLimit() throws Exception {
    var server = fixedResponseServer("four");
    try {
      var response = BoundedResponseBodyHandlers.send(
          HttpClient.newHttpClient(),
          request(server, Duration.ofSeconds(1)),
          BoundedResponseBodyHandlers.ofString(4, UTF_8, status -> status == 200));

      assertEquals("four", response.body());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsAResponseOneBytePastTheLimit() throws Exception {
    var server = fixedResponseServer("five!");
    try {
      assertThrows(
          BodyLimitExceededException.class,
          () -> BoundedResponseBodyHandlers.send(
              HttpClient.newHttpClient(),
              request(server, Duration.ofSeconds(1)),
              BoundedResponseBodyHandlers.ofByteArray(4, status -> status == 200)));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void requestTimeoutIncludesABodyThatStallsAfterHeaders() throws Exception {
    var bodyStarted = new CountDownLatch(1);
    var releaseBody = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", exchange -> {
      exchange.sendResponseHeaders(200, 128);
      exchange.getResponseBody().write('{');
      exchange.getResponseBody().flush();
      bodyStarted.countDown();
      try {
        releaseBody.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.start();
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var result = executor.submit(() -> BoundedResponseBodyHandlers.send(
        HttpClient.newHttpClient(),
        request(server, Duration.ofMillis(150)),
        BoundedResponseBodyHandlers.ofByteArray(128, status -> status == 200)));
    try {
      assertTrue(bodyStarted.await(1, TimeUnit.SECONDS));
      var exception = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
      assertTrue(exception.getCause() instanceof HttpTimeoutException);
    } finally {
      result.cancel(true);
      releaseBody.countDown();
      executor.close();
      server.stop(0);
    }
  }

  @Test
  void callerInterruptionCancelsTheBodyAndPreservesInterruptStatus() throws Exception {
    var bodyStarted = new CountDownLatch(1);
    var releaseBody = new CountDownLatch(1);
    var interruptedStatus = new AtomicBoolean();
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", exchange -> {
      exchange.sendResponseHeaders(200, 128);
      exchange.getResponseBody().write('{');
      exchange.getResponseBody().flush();
      bodyStarted.countDown();
      try {
        releaseBody.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.start();
    var caller = Thread.startVirtualThread(() -> {
      try {
        BoundedResponseBodyHandlers.send(
            HttpClient.newHttpClient(),
            request(server, Duration.ofSeconds(5)),
            BoundedResponseBodyHandlers.ofByteArray(128, status -> status == 200));
      } catch (InterruptedException e) {
        interruptedStatus.set(Thread.currentThread().isInterrupted());
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
    try {
      assertTrue(bodyStarted.await(1, TimeUnit.SECONDS));
      caller.interrupt();
      caller.join(Duration.ofSeconds(1));
      assertTrue(!caller.isAlive());
      assertTrue(interruptedStatus.get());
    } finally {
      releaseBody.countDown();
      server.stop(0);
    }
  }

  @Test
  void rejectsARequestWithoutADeadlineBeforeSending() {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:1/")).build();

    assertThrows(
        IllegalArgumentException.class,
        () -> BoundedResponseBodyHandlers.send(
            HttpClient.newHttpClient(),
            request,
            BoundedResponseBodyHandlers.ofByteArray(4, status -> true)));
  }

  private HttpServer fixedResponseServer(String body) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", exchange -> {
      var bytes = body.getBytes(UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    return server;
  }

  private HttpRequest request(HttpServer server, Duration timeout) {
    return HttpRequest.newBuilder(
            URI.create("http://localhost:" + server.getAddress().getPort() + "/"))
        .timeout(timeout)
        .build();
  }
}
