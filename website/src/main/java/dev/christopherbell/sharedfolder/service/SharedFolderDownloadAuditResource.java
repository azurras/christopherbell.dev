package dev.christopherbell.sharedfolder.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BiConsumer;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;

/** Counts response-stream bytes and emits exactly one terminal download outcome. */
public final class SharedFolderDownloadAuditResource extends AbstractResource {
  /** Closed terminal outcomes for an opened response body. */
  public enum Outcome { COMPLETED, ABORTED, FAILED }

  private final Resource delegate;
  private final long expectedBytes;
  private final BiConsumer<Outcome, Long> terminal;

  /** Wraps one bounded body without opening or buffering its underlying stream. */
  public SharedFolderDownloadAuditResource(
      Resource delegate, long expectedBytes, BiConsumer<Outcome, Long> terminal) {
    if (delegate == null || terminal == null || expectedBytes < 0) {
      throw new IllegalArgumentException("Download audit resource arguments are invalid");
    }
    this.delegate = delegate;
    this.expectedBytes = expectedBytes;
    this.terminal = terminal;
  }

  @Override
  public String getDescription() {
    return delegate.getDescription();
  }

  @Override
  public String getFilename() {
    return delegate.getFilename();
  }

  @Override
  public long contentLength() {
    return expectedBytes;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    try {
      return new CountingInputStream(delegate.getInputStream());
    } catch (IOException failure) {
      emit(Outcome.FAILED, 0);
      throw failure;
    }
  }

  private void emit(Outcome outcome, long bytes) {
    try {
      terminal.accept(outcome, bytes);
    } catch (RuntimeException ignored) {
      // Audit degradation must never interrupt an otherwise valid response body.
    }
  }

  private final class CountingInputStream extends FilterInputStream {
    private long delivered;
    private boolean terminalEmitted;

    private CountingInputStream(InputStream input) {
      super(input);
    }

    @Override
    public int read() throws IOException {
      try {
        int value = super.read();
        if (value >= 0) delivered++;
        else finish();
        return value;
      } catch (IOException failure) {
        fail();
        throw failure;
      }
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      try {
        int count = super.read(bytes, offset, length);
        if (count > 0) delivered += count;
        else if (count < 0) finish();
        return count;
      } catch (IOException failure) {
        fail();
        throw failure;
      }
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } catch (IOException failure) {
        fail();
        throw failure;
      } finally {
        if (!terminalEmitted) {
          if (delivered == expectedBytes) finish();
          else abort();
        }
      }
    }

    private void finish() {
      if (terminalEmitted) return;
      terminalEmitted = true;
      emit(delivered == expectedBytes ? Outcome.COMPLETED : Outcome.FAILED, delivered);
    }

    private void abort() {
      if (terminalEmitted) return;
      terminalEmitted = true;
      emit(Outcome.ABORTED, delivered);
    }

    private void fail() {
      if (terminalEmitted) return;
      terminalEmitted = true;
      emit(Outcome.FAILED, delivered);
    }
  }
}
