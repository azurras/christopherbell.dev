package dev.christopherbell.testsupport;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Local HTTP fixture that sends headers and one byte, then waits for explicit release. */
public final class HeadersThenStallServer implements AutoCloseable {
  private final CountDownLatch bodyStarted = new CountDownLatch(1);
  private final CountDownLatch releaseBody = new CountDownLatch(1);
  private final HttpServer server;

  public HeadersThenStallServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", exchange -> {
      exchange.getRequestBody().readAllBytes();
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
  }

  public URI uri(String path) {
    return URI.create("http://localhost:" + server.getAddress().getPort() + path);
  }

  /** Invokes one client call after proving the server began but did not finish its body. */
  public <T> T callWhileBodyStalls(Callable<T> call, Duration completionLimit) throws Exception {
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var result = executor.submit(call);
    try {
      if (!bodyStarted.await(1, TimeUnit.SECONDS)) {
        throw new AssertionError("client did not receive the stalled response body");
      }
      try {
        return result.get(completionLimit.toMillis(), TimeUnit.MILLISECONDS);
      } catch (ExecutionException e) {
        if (e.getCause() instanceof Exception exception) {
          throw exception;
        }
        if (e.getCause() instanceof Error error) {
          throw error;
        }
        throw new IllegalStateException("client failed without a throwable cause", e);
      }
    } finally {
      result.cancel(true);
      releaseBody.countDown();
      executor.close();
    }
  }

  @Override
  public void close() {
    releaseBody.countDown();
    server.stop(0);
  }
}
