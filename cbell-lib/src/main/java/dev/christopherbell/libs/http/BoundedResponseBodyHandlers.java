package dev.christopherbell.libs.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntPredicate;

/** JDK HTTP body handlers that admit response bytes only within a declared limit. */
public final class BoundedResponseBodyHandlers {
  private BoundedResponseBodyHandlers() {}

  /**
   * Sends a request and keeps its configured timeout effective until the body is complete.
   *
   * <p>The JDK request timeout alone does not abort a body that stalls after response headers.
   * This boundary waits on the complete asynchronous exchange for the same total duration and
   * cancels the exchange on timeout or caller interruption.
   *
   * @param client client that owns the HTTP exchange
   * @param request request with a configured timeout
   * @param bodyHandler bounded handler for the response body
   * @return the complete response
   * @throws IOException when the exchange or response body fails
   * @throws InterruptedException when the caller is interrupted
   */
  public static <T> HttpResponse<T> send(
      HttpClient client,
      HttpRequest request,
      HttpResponse.BodyHandler<T> bodyHandler
  ) throws IOException, InterruptedException {
    Objects.requireNonNull(client, "client");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(bodyHandler, "bodyHandler");
    var timeout = request.timeout()
        .orElseThrow(() -> new IllegalArgumentException("request timeout is required"));
    final long timeoutNanos;
    try {
      timeoutNanos = timeout.toNanos();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("request timeout is too large", e);
    }
    var startedAt = System.nanoTime();
    var response = client.sendAsync(request, bodyHandler);
    try {
      var elapsedNanos = System.nanoTime() - startedAt;
      var remainingNanos = timeoutNanos - elapsedNanos;
      if (remainingNanos <= 0) {
        throw new TimeoutException("request timed out");
      }
      return response.get(remainingNanos, TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      response.cancel(true);
      throw new HttpTimeoutException("request timed out");
    } catch (InterruptedException e) {
      response.cancel(true);
      Thread.currentThread().interrupt();
      throw e;
    } catch (ExecutionException e) {
      throw translateFailure(e.getCause());
    }
  }

  private static IOException translateFailure(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof BodyLimitExceededException bodyLimitExceeded) {
        return bodyLimitExceeded;
      }
    }
    if (failure instanceof IOException ioFailure) {
      return ioFailure;
    }
    return new IOException("HTTP request failed", failure);
  }

  /**
   * Creates a byte-array handler whose completion remains tied to the full response body.
   *
   * @param maximumBytes largest body that may be returned
   * @param shouldReadStatus feature-owned decision for statuses whose bodies should be read
   * @return a bounded handler that cancels bodies the feature does not need
   */
  public static HttpResponse.BodyHandler<byte[]> ofByteArray(
      long maximumBytes,
      IntPredicate shouldReadStatus
  ) {
    validateMaximum(maximumBytes);
    Objects.requireNonNull(shouldReadStatus, "shouldReadStatus");
    return responseInfo -> shouldReadStatus.test(responseInfo.statusCode())
        ? new BoundedByteArraySubscriber(maximumBytes)
        : new CancelingSubscriber<>(new byte[0]);
  }

  /**
   * Creates a string handler whose completion remains tied to the full response body.
   *
   * @param maximumBytes largest encoded body that may be decoded
   * @param charset response character set
   * @param shouldReadStatus feature-owned decision for statuses whose bodies should be read
   * @return a bounded handler that cancels bodies the feature does not need
   */
  public static HttpResponse.BodyHandler<String> ofString(
      long maximumBytes,
      Charset charset,
      IntPredicate shouldReadStatus
  ) {
    validateMaximum(maximumBytes);
    Objects.requireNonNull(charset, "charset");
    Objects.requireNonNull(shouldReadStatus, "shouldReadStatus");
    return responseInfo -> shouldReadStatus.test(responseInfo.statusCode())
        ? HttpResponse.BodySubscribers.mapping(
            new BoundedByteArraySubscriber(maximumBytes),
            bytes -> new String(bytes, charset))
        : new CancelingSubscriber<>("");
  }

  private static void validateMaximum(long maximumBytes) {
    if (maximumBytes < 0 || maximumBytes > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("maximum response bytes are invalid");
    }
  }

  private static final class BoundedByteArraySubscriber
      implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final List<byte[]> chunks = new ArrayList<>();
    private final long maximumBytes;
    private Flow.Subscription subscription;
    private long totalBytes;
    private boolean complete;

    private BoundedByteArraySubscriber(long maximumBytes) {
      this.maximumBytes = maximumBytes;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription newSubscription) {
      Objects.requireNonNull(newSubscription, "newSubscription");
      if (subscription != null) {
        newSubscription.cancel();
        return;
      }
      subscription = newSubscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (complete) {
        return;
      }
      long incomingBytes = 0;
      for (var buffer : buffers) {
        incomingBytes += buffer.remaining();
        if (incomingBytes > maximumBytes - totalBytes) {
          fail(new BodyLimitExceededException(maximumBytes));
          return;
        }
      }
      for (var buffer : buffers) {
        var chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        chunks.add(chunk);
      }
      totalBytes += incomingBytes;
      subscription.request(1);
    }

    @Override
    public void onError(Throwable failure) {
      if (complete) {
        return;
      }
      complete = true;
      chunks.clear();
      body.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
      if (complete) {
        return;
      }
      complete = true;
      var result = new byte[(int) totalBytes];
      var offset = 0;
      for (var chunk : chunks) {
        System.arraycopy(chunk, 0, result, offset, chunk.length);
        offset += chunk.length;
      }
      chunks.clear();
      body.complete(result);
    }

    private void fail(Throwable failure) {
      complete = true;
      chunks.clear();
      subscription.cancel();
      body.completeExceptionally(failure);
    }
  }

  private static final class CancelingSubscriber<T> implements HttpResponse.BodySubscriber<T> {
    private final CompletionStage<T> body;

    private CancelingSubscriber(T replacement) {
      body = CompletableFuture.completedFuture(replacement);
    }

    @Override
    public CompletionStage<T> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.cancel();
    }

    @Override
    public void onNext(List<ByteBuffer> item) {}

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onComplete() {}
  }
}
