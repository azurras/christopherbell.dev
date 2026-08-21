package dev.christopherbell.admin.commandcenter.metrics;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = DatabaseHealthHttpSecurityIntegrationTest.TestApplication.class,
    properties = {
        "management.endpoints.web.exposure.include=health",
        "management.endpoint.health.show-details=when-authorized",
        "management.endpoint.health.roles=ADMIN",
        "management.endpoint.health.probes.enabled=true",
        "management.endpoint.health.group.readiness.include=readinessState,database",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
@AutoConfigureMockMvc
class DatabaseHealthHttpSecurityIntegrationTest {
  @Autowired private MockMvc mvc;

  @Test
  void rootHealthRequiresAuthentication() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isForbidden());
  }

  @Test
  void nonAdminHealthOmitsComponentsAndDetails() throws Exception {
    mvc.perform(get("/actuator/health").with(user("member").roles("USER")))
        .andExpect(status().isOk())
        .andExpect(content().json(
            "{\"groups\":[\"liveness\",\"readiness\"],\"status\":\"UP\"}", true));
  }

  @Test
  void adminHealthExposesOnlyTheDeclaredDatabaseIdentityDetails() throws Exception {
    mvc.perform(get("/actuator/health").with(user("administrator").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.database.details.backend").value("postgresql"))
        .andExpect(jsonPath("$.components.database.details.database").value("test"))
        .andExpect(jsonPath("$.components.database.details.schemaVersion").value("27"))
        .andExpect(jsonPath("$.components.database.details", not(hasKey("username"))))
        .andExpect(content().string(not(org.hamcrest.Matchers.containsString("password"))))
        .andExpect(content().string(not(org.hamcrest.Matchers.containsString("jdbc:"))));
  }

  @Test
  void publicProbeGroupsRemainDetailFree() throws Exception {
    mvc.perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}", true));
    mvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}", true));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(DatabaseHealthConfiguration.class)
  static class TestApplication {
    @Bean
    DatabaseConnectivityProbe databaseConnectivityProbe() {
      return timeout -> timeout.equals(Duration.ofSeconds(2));
    }

    @Bean
    PersistenceIdentityProbe persistenceIdentityProbe() {
      return timeout -> new PersistenceIdentity("postgresql", "test", "27");
    }

    @Bean
    SecurityFilterChain healthSecurity(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(authorize -> authorize
              .requestMatchers(HttpMethod.GET,
                  "/actuator/health/liveness", "/actuator/health/readiness")
              .permitAll()
              .anyRequest().authenticated())
          .build();
    }
  }
}
