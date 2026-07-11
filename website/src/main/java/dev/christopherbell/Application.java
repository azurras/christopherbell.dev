package dev.christopherbell;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot application entry point.
 *
 * <p>Bootstraps the application context and starts the embedded web server.</p>
 */
@SpringBootApplication
public class Application {
  /**
   * Starts the Spring Boot application.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
