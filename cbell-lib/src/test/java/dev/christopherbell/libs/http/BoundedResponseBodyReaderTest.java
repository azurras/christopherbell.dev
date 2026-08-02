package dev.christopherbell.libs.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import org.junit.jupiter.api.Test;

class BoundedResponseBodyReaderTest {
  @Test
  void acceptsAResponseAtTheExactLimitAndClosesTheStream() throws Exception {
    var input = new CloseTrackingInputStream("four".getBytes(UTF_8));

    assertEquals("four", BoundedResponseBodyReader.readString(input, 4, UTF_8));
    assertTrue(input.closed);
  }

  @Test
  void rejectsOneBytePastTheLimitAndClosesTheStream() {
    var input = new CloseTrackingInputStream(new byte[9]);

    var exception = assertThrows(
        BodyLimitExceededException.class,
        () -> BoundedResponseBodyReader.read(input, 8));
    assertTrue(exception.getMessage().contains("8 byte limit"));
    assertTrue(input.closed);
  }

  @Test
  void propagatesAnInterruptedReadAndClosesTheStream() {
    var input = new InterruptingInputStream();

    var exception = assertThrows(
        InterruptedIOException.class,
        () -> BoundedResponseBodyReader.read(input, 8));
    assertEquals("read interrupted", exception.getMessage());
    assertTrue(input.closed);
  }

  @Test
  void rejectsInvalidMaximumBeforeReading() {
    assertThrows(
        IllegalArgumentException.class,
        () -> BoundedResponseBodyReader.read(new ByteArrayInputStream(new byte[0]), -1));
  }

  private static final class CloseTrackingInputStream extends ByteArrayInputStream {
    private boolean closed;

    private CloseTrackingInputStream(byte[] bytes) {
      super(bytes);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }

  private static final class InterruptingInputStream extends InputStream {
    private boolean closed;

    @Override
    public int read() throws IOException {
      throw new InterruptedIOException("read interrupted");
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      throw new InterruptedIOException("read interrupted");
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
