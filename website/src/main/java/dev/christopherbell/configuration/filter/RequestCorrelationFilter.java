package dev.christopherbell.configuration.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation ID that is returned in {@code X-Request-Id} and attached to
 * each log line written while the request is handled, so a reported failure can be matched to
 * its logs. A well-formed inbound ID from a proxy or client is kept; anything else is replaced so
 * untrusted text never reaches headers or logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  public static final String REQUEST_ID_LOG_KEY = "requestId";
  private static final Pattern ACCEPTED_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    var requestId = acceptedRequestIdFrom(request.getHeader(REQUEST_ID_HEADER))
        .orElseGet(() -> UUID.randomUUID().toString());
    response.setHeader(REQUEST_ID_HEADER, requestId);
    MDC.put(REQUEST_ID_LOG_KEY, requestId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(REQUEST_ID_LOG_KEY);
    }
  }

  private static Optional<String> acceptedRequestIdFrom(String inboundRequestId) {
    if (inboundRequestId == null || !ACCEPTED_REQUEST_ID.matcher(inboundRequestId).matches()) {
      return Optional.empty();
    }
    return Optional.of(inboundRequestId);
  }
}
