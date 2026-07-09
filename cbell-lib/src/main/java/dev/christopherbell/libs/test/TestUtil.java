package dev.christopherbell.libs.test;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import lombok.experimental.UtilityClass;

/**
 * Utility class for reading JSON files from the classpath and converting them
 * into Java objects or strings. This is particularly useful for testing purposes,
 * such as loading test data or request/response payloads.
 */
@UtilityClass
public final class TestUtil {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /**
   * Reads a JSON file from the classpath and converts it into the given type.
   *
   * @param path the classpath location of the JSON (e.g., "/requests/create-restaurant-request.json")
   * @param clazz the class to deserialize into
   * @return the deserialized object
   */
  public static <T> T readJsonAsObject(String path, Class<T> clazz) {
    try (InputStream is = TestUtil.class.getResourceAsStream(path)) {
      if (is == null) {
        throw new IllegalArgumentException("File not found on classpath: " + path);
      }
      return OBJECT_MAPPER.readValue(is, clazz);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read JSON from: " + path, e);
    }
  }

  /**
   * Reads a JSON file from the classpath into a String.
   *
   * @param path the classpath location of the JSON
   * @return JSON string
   */
  public static String readJsonAsString(String path) {
    try (InputStream is = TestUtil.class.getResourceAsStream(path)) {
      if (is == null) {
        throw new IllegalArgumentException("File not found on classpath: " + path);
      }
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read JSON from: " + path, e);
    }
  }
}
