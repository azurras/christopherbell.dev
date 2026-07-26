package dev.christopherbell.configuration.filter;

import dev.christopherbell.configuration.RequestSizeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.util.unit.DataSize;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Servlet filter that rejects requests exceeding a configured maximum size.
 *
 * <p>Defaults to 1 MB when no explicit limit is provided.</p>
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

  private static final String REQUEST_TOO_LARGE = "REQUEST_TOO_LARGE";
  private static final String REQUEST_TOO_LARGE_DESCRIPTION =
      "The request body exceeds the allowed size.";

  private static final java.util.regex.Pattern SHARED_UPLOAD_CHUNK = java.util.regex.Pattern.compile(
      "^/api/shared-folder/2026-07-17/uploads/[^/]+/chunks/[0-9]+$");

  private final long maxSizeBytes;
  private final long sharedUploadChunkMaxSizeBytes;
  private final ApiErrorResponseWriter errors;

  /**
   * Creates a filter with a default limit of 1 MB.
   */
  public RequestSizeLimitFilter() {
    this(1_000_000L, 8L * 1024 * 1024, new ApiErrorResponseWriter(new ObjectMapper()));
  }

  /**
   * Creates a filter with a custom size limit. Intended for testing or configuration.
   *
   * @param maxSizeBytes maximum allowed request size in bytes
   */
  public RequestSizeLimitFilter(long maxSizeBytes) {
    this(maxSizeBytes, 8L * 1024 * 1024, new ApiErrorResponseWriter(new ObjectMapper()));
  }

  /** Creates route-aware limits for ordinary requests and streamed shared-folder chunks. */
  public RequestSizeLimitFilter(long maxSizeBytes, long sharedUploadChunkMaxSizeBytes) {
    this(
        maxSizeBytes,
        sharedUploadChunkMaxSizeBytes,
        new ApiErrorResponseWriter(new ObjectMapper()));
  }

  private RequestSizeLimitFilter(
      long maxSizeBytes,
      long sharedUploadChunkMaxSizeBytes,
      ApiErrorResponseWriter errors
  ) {
    if (maxSizeBytes <= 0 || sharedUploadChunkMaxSizeBytes <= 0) {
      throw new IllegalArgumentException("request size limits must be positive");
    }
    if (maxSizeBytes >= Integer.MAX_VALUE || sharedUploadChunkMaxSizeBytes >= Integer.MAX_VALUE) {
      throw new IllegalArgumentException("request size limits must be smaller than 2 GB");
    }
    this.maxSizeBytes = maxSizeBytes;
    this.sharedUploadChunkMaxSizeBytes = sharedUploadChunkMaxSizeBytes;
    this.errors = errors;
  }

  /** Creates route-aware typed limits for ordinary requests and shared-folder chunks. */
  public RequestSizeLimitFilter(
      RequestSizeProperties properties,
      DataSize sharedUploadChunkMax,
      ApiErrorResponseWriter errors
  ) {
    this(properties.defaultMax().toBytes(), sharedUploadChunkMax.toBytes(), errors);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    long limit = "PUT".equalsIgnoreCase(request.getMethod())
        && SHARED_UPLOAD_CHUNK.matcher(request.getRequestURI()).matches()
        ? sharedUploadChunkMaxSizeBytes : maxSizeBytes;
    long contentLength = request.getContentLengthLong();
    if (contentLength > limit) {
      reject(request, response);
      return;
    }

    try {
      HttpServletRequest boundedRequest = contentLength < 0
          ? cacheUnknownLengthBody(request, limit)
          : new SizeLimitedRequestWrapper(request, limit);
      filterChain.doFilter(boundedRequest, response);
    } catch (RequestPayloadTooLargeException e) {
      reject(request, response);
    }
  }

  private HttpServletRequest cacheUnknownLengthBody(HttpServletRequest request, long limit)
      throws IOException {
    byte[] body = request.getInputStream().readNBytes(Math.toIntExact(limit + 1));
    if (body.length > limit) {
      throw new RequestPayloadTooLargeException();
    }
    return new CachedBodyRequestWrapper(request, body);
  }

  private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (isJson(request)) {
      errors.write(
          response,
          HttpStatus.PAYLOAD_TOO_LARGE.value(),
          REQUEST_TOO_LARGE,
          REQUEST_TOO_LARGE_DESCRIPTION);
      return;
    }
    if (!response.isCommitted()) {
      response.resetBuffer();
      response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
    }
  }

  private boolean isJson(HttpServletRequest request) {
    String contentType = request.getContentType();
    if (contentType == null) {
      return false;
    }
    String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    return "application/json".equals(mediaType) || mediaType.endsWith("+json");
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

  private static class CachedBodyRequestWrapper extends HttpServletRequestWrapper {
    private final byte[] body;

    CachedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
      return new CachedBodyServletInputStream(body);
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

  private static class CachedBodyServletInputStream extends ServletInputStream {
    private final ByteArrayInputStream delegate;

    CachedBodyServletInputStream(byte[] body) {
      this.delegate = new ByteArrayInputStream(body);
    }

    @Override
    public int read() {
      return delegate.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      return delegate.read(buffer, offset, length);
    }

    @Override
    public boolean isFinished() {
      return delegate.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      try {
        if (isFinished()) {
          readListener.onAllDataRead();
        } else {
          readListener.onDataAvailable();
        }
      } catch (IOException e) {
        readListener.onError(e);
      }
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

}
