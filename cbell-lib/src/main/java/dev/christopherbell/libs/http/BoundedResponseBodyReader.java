package dev.christopherbell.libs.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/** Reads a remote body without materializing bytes beyond a declared maximum. */
public final class BoundedResponseBodyReader {
  private static final int BUFFER_SIZE = 8_192;

  private BoundedResponseBodyReader() {}

  /**
   * Reads and closes a response stream while enforcing a maximum byte count.
   *
   * @param input response stream owned by this call
   * @param maximumBytes largest response that may be returned
   * @return the response bytes
   * @throws BodyLimitExceededException when the response exceeds the maximum
   * @throws IOException when reading or closing the response fails
   */
  public static byte[] read(InputStream input, long maximumBytes) throws IOException {
    if (maximumBytes < 0 || maximumBytes > Integer.MAX_VALUE - 1L) {
      throw new IllegalArgumentException("maximum response bytes are invalid");
    }

    try (input;
        var output = new ByteArrayOutputStream((int) Math.min(maximumBytes, BUFFER_SIZE))) {
      var buffer = new byte[BUFFER_SIZE];
      long total = 0;
      while (true) {
        var remainingWithOverflowByte = maximumBytes - total + 1;
        var count = input.read(buffer, 0, (int) Math.min(BUFFER_SIZE, remainingWithOverflowByte));
        if (count < 0) {
          return output.toByteArray();
        }
        total += count;
        if (total > maximumBytes) {
          throw new BodyLimitExceededException(maximumBytes);
        }
        output.write(buffer, 0, count);
      }
    }
  }

  /**
   * Reads and decodes a bounded response stream.
   *
   * @param input response stream owned by this call
   * @param maximumBytes largest encoded response that may be returned
   * @param charset response character set
   * @return the decoded response body
   * @throws BodyLimitExceededException when the response exceeds the maximum
   * @throws IOException when reading or closing the response fails
   */
  public static String readString(InputStream input, long maximumBytes, Charset charset)
      throws IOException {
    return new String(read(input, maximumBytes), charset);
  }
}
