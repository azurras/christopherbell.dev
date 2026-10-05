package dev.christopherbell.configuration.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTest {
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

  @Test
  void wellFormedInboundRequestIdIsEchoedAndLoggedDuringTheRequest() throws Exception {
    var request = requestWithRequestId("cf-ray.8c2f9a1b2c3d4e5f-DFW");
    var response = new MockHttpServletResponse();
    var requestIdSeenDownstream = new AtomicReference<String>();

    filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
        requestIdSeenDownstream.set(MDC.get(RequestCorrelationFilter.REQUEST_ID_LOG_KEY)));

    assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER))
        .isEqualTo("cf-ray.8c2f9a1b2c3d4e5f-DFW");
    assertThat(requestIdSeenDownstream.get()).isEqualTo("cf-ray.8c2f9a1b2c3d4e5f-DFW");
    assertThat(MDC.get(RequestCorrelationFilter.REQUEST_ID_LOG_KEY)).isNull();
  }

  @Test
  void missingRequestIdIsGenerated() throws Exception {
    var response = new MockHttpServletResponse();

    filter.doFilter(new MockHttpServletRequest("GET", "/"), response, (request, ignored) -> { });

    assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER))
        .matches(UUID_PATTERN);
  }

  @Test
  void unsafeOrOversizedInboundRequestIdsAreReplacedRatherThanEchoed() throws Exception {
    var unsafeRequestIds = new String[] {
        "abc\r\nInjected: header",
        "short",
        "x".repeat(65),
        "spaces are not allowed",
        "<script>alert(1)</script>",
    };

    for (var unsafeRequestId : unsafeRequestIds) {
      var response = new MockHttpServletResponse();
      filter.doFilter(requestWithRequestId(unsafeRequestId), response, (request, ignored) -> { });

      assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER))
          .as("replacement for %s", unsafeRequestId)
          .matches(UUID_PATTERN);
    }
  }

  @Test
  void requestIdIsClearedEvenWhenDownstreamFails() {
    var response = new MockHttpServletResponse();

    assertThatThrownBy(() -> filter.doFilter(
        requestWithRequestId("request-0123456789"), response, (request, ignored) -> {
          throw new ServletException("downstream failure");
        }))
        .isInstanceOf(ServletException.class)
        .hasMessage("downstream failure");
    assertThat(MDC.get(RequestCorrelationFilter.REQUEST_ID_LOG_KEY)).isNull();
    assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER))
        .isEqualTo("request-0123456789");
  }

  private static MockHttpServletRequest requestWithRequestId(String requestId) {
    var request = new MockHttpServletRequest("GET", "/blog");
    request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, requestId);
    return request;
  }
}
