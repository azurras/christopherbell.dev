package dev.christopherbell.configuration.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that rejects requests exceeding a configured maximum size.
 *
 * <p>Defaults to 1 MB when no explicit limit is provided.</p>
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

  private final long maxSizeBytes;

  /**
   * Creates a filter with a default limit of 1 MB.
   */
  public RequestSizeLimitFilter() {
    this(1_000_000L);
  }

  /**
   * Creates a filter with a custom size limit. Intended for testing or configuration.
   *
   * @param maxSizeBytes maximum allowed request size in bytes
   */
  public RequestSizeLimitFilter(long maxSizeBytes) {
    this.maxSizeBytes = maxSizeBytes;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    long contentLength = request.getContentLengthLong();
    if (contentLength > maxSizeBytes) {
      response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
      return;
    }

    try {
      filterChain.doFilter(new SizeLimitedRequestWrapper(request, maxSizeBytes), response);
    } catch (RequestPayloadTooLargeException e) {
      response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
    }
  }

  private static class SizeLimitedRequestWrapper extends HttpServletRequestWrapper {
    private final long maxSizeBytes;

    SizeLimitedRequestWrapper(HttpServletRequest request, long maxSizeBytes) {
      super(request);
      this.maxSizeBytes = maxSizeBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      return new SizeLimitedServletInputStream(super.getInputStream(), maxSizeBytes);
    }

    @Override
    public BufferedReader getReader() throws IOException {
      var encoding = getCharacterEncoding();
      Charset charset = encoding == null || encoding.isBlank()
          ? StandardCharsets.UTF_8
          : Charset.forName(encoding);
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }

  private static class SizeLimitedServletInputStream extends ServletInputStream {
    private final ServletInputStream delegate;
    private final long maxSizeBytes;
    private long bytesRead;

    SizeLimitedServletInputStream(ServletInputStream delegate, long maxSizeBytes) {
      this.delegate = delegate;
      this.maxSizeBytes = maxSizeBytes;
    }

    @Override
    public int read() throws IOException {
      int value = delegate.read();
      if (value != -1) {
        countBytes(1);
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int read = delegate.read(buffer, offset, length);
      if (read > 0) {
        countBytes(read);
      }
      return read;
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      delegate.setReadListener(readListener);
    }

    private void countBytes(int count) throws RequestPayloadTooLargeException {
      bytesRead += count;
      if (bytesRead > maxSizeBytes) {
        throw new RequestPayloadTooLargeException();
      }
    }
  }

  private static class RequestPayloadTooLargeException extends IOException {
  }
}
