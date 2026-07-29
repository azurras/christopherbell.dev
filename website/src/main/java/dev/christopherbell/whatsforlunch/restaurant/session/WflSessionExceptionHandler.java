package dev.christopherbell.whatsforlunch.restaurant.session;

import dev.christopherbell.libs.api.model.Message;
import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantController;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Publishes stable WFL session conflict codes without exposing persistence details. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = RestaurantController.class)
public class WflSessionExceptionHandler {
  @ExceptionHandler(WflSessionConflictException.class)
  public ResponseEntity<Response<?>> handleConflict(WflSessionConflictException conflict) {
    var body = Response.builder()
        .success(false)
        .messages(List.of(Message.builder()
            .code(conflict.code())
            .description(description(conflict.code()))
            .build()))
        .build();
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  private String description(String code) {
    return switch (code) {
      case "WFL_SESSION_FULL" -> "This lunch session is full.";
      case "WFL_SESSION_EXPIRED" -> "This lunch session is archived and cannot be changed.";
      case "WFL_SESSION_CHANGED" -> "This lunch session changed. Refresh and try again.";
      default -> "This lunch session cannot be changed.";
    };
  }
}
