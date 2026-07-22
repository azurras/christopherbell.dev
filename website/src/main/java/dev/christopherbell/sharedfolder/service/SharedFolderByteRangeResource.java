package dev.christopherbell.sharedfolder.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;

/** A pathless resource that exposes exactly one bounded region of another resource. */
final class SharedFolderByteRangeResource extends AbstractResource {
  private final Resource source;
  private final long start;
  private final long length;

  SharedFolderByteRangeResource(Resource source, long start, long length) {
    this.source = Objects.requireNonNull(source, "source is required");
    if (start < 0 || length < 0) {
      throw new IllegalArgumentException("byte range must be non-negative");
    }
    this.start = start;
    this.length = length;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    InputStream input = source.getInputStream();
    try {
      input.skipNBytes(start);
      return new BoundedInputStream(input, length);
    } catch (IOException | RuntimeException failure) {
      input.close();
      throw failure;
    }
  }

  @Override
  public long contentLength() {
    return length;
  }

  @Override
  public String getFilename() {
    return source.getFilename();
  }

  @Override
  public String getDescription() {
    return "shared-folder byte-range resource";
  }

  private static final class BoundedInputStream extends FilterInputStream {
    private long remaining;

    private BoundedInputStream(InputStream input, long length) {
      super(input);
      remaining = length;
    }

    @Override
    public int read() throws IOException {
      if (remaining == 0) {
        return -1;
      }
      int value = super.read();
      if (value == -1) {
        throw new IOException("Shared-folder file ended before the selected byte range");
      }
      remaining--;
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int requestedLength) throws IOException {
      Objects.checkFromIndexSize(offset, requestedLength, bytes.length);
      if (requestedLength == 0) {
        return 0;
      }
      if (remaining == 0) {
        return -1;
      }
      int boundedLength = (int) Math.min(remaining, requestedLength);
      int read = super.read(bytes, offset, boundedLength);
      if (read == -1) {
        throw new IOException("Shared-folder file ended before the selected byte range");
      }
      remaining -= read;
      return read;
    }

    @Override
    public long skip(long requested) throws IOException {
      if (requested <= 0 || remaining == 0) {
        return 0;
      }
      long skipped = super.skip(Math.min(remaining, requested));
      remaining -= skipped;
      return skipped;
    }

    @Override
    public int available() throws IOException {
      return (int) Math.min(remaining, super.available());
    }
  }
}
