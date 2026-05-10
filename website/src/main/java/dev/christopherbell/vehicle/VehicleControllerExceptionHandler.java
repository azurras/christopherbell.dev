package dev.christopherbell.vehicle;

import dev.christopherbell.libs.api.model.Message;
import dev.christopherbell.libs.api.model.Response;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = VehicleController.class)
public class VehicleControllerExceptionHandler {
  @ExceptionHandler(VehicleVinDecodeRateLimitException.class)
  @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
  public Response<?> handleVinDecodeRateLimit(VehicleVinDecodeRateLimitException e) {
    return error("VIN_DECODE_RATE_LIMITED", e.getMessage());
  }

  @ExceptionHandler(VehicleVinDecodeUnavailableException.class)
  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  public Response<?> handleVinDecodeUnavailable(VehicleVinDecodeUnavailableException e) {
    return error("VIN_DECODE_UNAVAILABLE", e.getMessage());
  }

  private Response<?> error(String code, String description) {
    return Response.builder()
        .messages(List.of(Message.builder()
            .code(code)
            .description(description)
            .build()))
        .success(false)
        .build();
  }
}
