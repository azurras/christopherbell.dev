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
            .description(conflict.conflict().description())
            .build()))
        .build();
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }
}
