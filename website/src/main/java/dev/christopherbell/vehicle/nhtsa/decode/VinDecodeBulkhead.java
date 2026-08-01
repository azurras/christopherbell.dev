package dev.christopherbell.vehicle.nhtsa.decode;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Owns the finite per-instance concurrency budget for public VIN upstream calls. */
@Component
final class VinDecodeBulkhead {
  private final Semaphore permits;

  VinDecodeBulkhead() {
    this(8);
  }

  VinDecodeBulkhead(int maximumConcurrentRequests) {
    permits = new Semaphore(maximumConcurrentRequests, true);
  }

  Optional<Permit> tryAcquire() {
    return permits.tryAcquire() ? Optional.of(new Permit(permits)) : Optional.empty();
  }

  static final class Permit implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Semaphore permits;

    private Permit(Semaphore permits) {
      this.permits = permits;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        permits.release();
      }
    }
  }
}
