package dev.christopherbell.account;

import dev.christopherbell.libs.api.model.Message;
import dev.christopherbell.libs.api.model.Response;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Controller advice for account-related errors.
 *
 * <p>Translates domain exceptions into consistent API error responses using the
 * shared {@link Response} envelope and {@link Message} entries.</p>
 */
@RestControllerAdvice
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccountControllerExceptionHandler {
  private static final String ACCOUNT_NOT_ACTIVE_CODE = "ACCOUNT_NOT_ACTIVE";

  /**
   * Handles {@link AccountNotActiveException} and returns HTTP 400 with a descriptive error.
   *
   * @param notActive the thrown exception
   * @return a {@link Response} with {@code success=false} and an error {@link Message}
   */
  @ExceptionHandler({AccountNotActiveException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Response<?> handleAccountNotActiveException(AccountNotActiveException notActive) {
    log.error(ACCOUNT_NOT_ACTIVE_CODE, notActive);
    return Response.builder()
        .messages(List.of(Message.builder()
            .code(ACCOUNT_NOT_ACTIVE_CODE)
            .description(notActive.getMessage())
            .build()))
        .success(false)
        .build();
  }
}
