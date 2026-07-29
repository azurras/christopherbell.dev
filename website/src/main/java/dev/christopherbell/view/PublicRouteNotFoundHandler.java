package dev.christopherbell.view;

import dev.christopherbell.libs.api.model.Message;
import dev.christopherbell.libs.api.model.Response;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Preserves true 404 behavior when no controller or static resource matches a request. */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicRouteNotFoundHandler {

  /** Renders browser routes as HTML while retaining a structured API response. */
  @ExceptionHandler(NoResourceFoundException.class)
  public Object handleNoResource(NoResourceFoundException exception, HttpServletRequest request) {
    if (request.getRequestURI().startsWith("/api/")) {
      var body = Response.builder()
          .messages(List.of(Message.builder()
              .code("RESOURCE_NOT_FOUND")
              .description("The requested API resource was not found.")
              .build()))
          .success(false)
          .build();
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body);
    }

    var page = new ModelAndView("error/404");
    page.setStatus(HttpStatus.NOT_FOUND);
    return page;
  }
}
