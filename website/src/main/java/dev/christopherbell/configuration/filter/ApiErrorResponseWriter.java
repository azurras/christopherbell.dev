package dev.christopherbell.configuration.filter;

import dev.christopherbell.libs.api.model.Message;
import dev.christopherbell.libs.api.model.Response;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/** Writes safe API errors using the application-wide response envelope. */
public final class ApiErrorResponseWriter {
  private final ObjectMapper objectMapper;

  public ApiErrorResponseWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Replaces an uncommitted response body with one public error message. */
  public void write(HttpServletResponse response, int status, String code, String description)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.resetBuffer();
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    var body = Response.builder()
        .messages(List.of(Message.builder()
            .code(code)
            .description(description)
            .build()))
        .success(false)
        .build();
    objectMapper.writeValue(response.getWriter(), body);
  }
}
