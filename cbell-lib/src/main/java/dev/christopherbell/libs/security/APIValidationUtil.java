package dev.christopherbell.libs.security;

import dev.christopherbell.libs.api.APIConstants;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Utility class for validating API requests and resources.
 */
@UtilityClass
public final class APIValidationUtil {

  /**
   * Checks to see if a request's clientId is allowed to make the request. If not, throw an InvalidResourceException.
   *
   * @param acceptedClients Allowed client IDs.
   * @param requestClientId The client ID of the request.
   * @return true if the client id is allowed.
   * @throws InvalidRequestException if the client id is not allowed.
   */
  public static boolean isValidClientId(
      List<String> acceptedClients,
      String requestClientId
  ) throws InvalidRequestException {
    if (!acceptedClients.contains(requestClientId)) {
      throw new InvalidRequestException(APIConstants.VALIDATION_BAD_CLIENT_ID);
    }
    return true;
  }

  /**
   * Checks to see if some string is blank, empty, or null. If it is, then throw an InvalidRequestException. The idea is
   * to use this on required fields.
   *
   * @param errorMessage         Error message to use for the exception.
   * @param questionableResource The string we want to test.
   * @return true if the resource is valid.
   * @throws InvalidRequestException if the resource is not valid.
   */
  public static boolean isValidResource(
      String errorMessage,
      String questionableResource
  ) throws InvalidRequestException {
    if (questionableResource == null || questionableResource.isBlank()) {
      throw new InvalidRequestException(errorMessage);
    }
    return true;
  }
}