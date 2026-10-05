package dev.christopherbell.configuration;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the packaged info settings expose the running build version to anonymous monitors
 * without leaking environment, runtime, or host details.
 */
@SpringBootTest(
    classes = ActuatorInfoHttpSecurityIntegrationTest.TestApplication.class,
    properties = {
        "management.endpoints.web.exposure.include=health,info",
        "management.info.env.enabled=false",
        "management.info.java.enabled=false",
        "management.info.os.enabled=false",
        "management.info.process.enabled=false",
        "management.info.ssl.enabled=false",
        "info.leaked=should-not-appear",
        "management.health.mongodb.enabled=false",
        "management.endpoint.health.group.readiness.include=readinessState",
    })
@AutoConfigureMockMvc
class ActuatorInfoHttpSecurityIntegrationTest {
  private static final String RELEASE_VERSION =
      "0.0.0-dev.a9d205890123456789abcdef0123456789abcdef";

  @Autowired private MockMvc mvc;

  @Test
  void anonymousInfoReportsOnlyTheBuildIdentity() throws Exception {
    mvc.perform(get("/actuator/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.build.version").value(RELEASE_VERSION))
        .andExpect(jsonPath("$.build.version", matchesPattern("0\\.0\\.0-dev\\.[0-9a-f]{40}")))
        .andExpect(jsonPath("$.leaked").doesNotExist())
        .andExpect(jsonPath("$.java").doesNotExist())
        .andExpect(jsonPath("$.os").doesNotExist())
        .andExpect(jsonPath("$.process").doesNotExist());
  }

  @Test
  void environmentEndpointStaysUnexposed() throws Exception {
    mvc.perform(get("/actuator/env"))
        .andExpect(status().isNotFound());
  }

  // A plain @Configuration, not @SpringBootConfiguration: slice tests in sibling packages
  // search parent packages for a @SpringBootConfiguration and must keep finding the real app.
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class TestApplication {
    @Bean
    BuildProperties buildProperties() {
      var entries = new Properties();
      entries.setProperty("version", RELEASE_VERSION);
      entries.setProperty("name", "website");
      return new BuildProperties(entries);
    }

    @Bean
    SecurityFilterChain infoSecurity(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(authorize -> authorize
              .requestMatchers(HttpMethod.GET, "/actuator/info").permitAll()
              .anyRequest().permitAll())
          .build();
    }
  }
}
