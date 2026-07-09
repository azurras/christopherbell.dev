package dev.christopherbell.configuration.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@TestConfiguration
@EnableWebSecurity
public class ControllerSliceSecurityTestConfig {

  @Bean
  public SecurityFilterChain controllerSliceSecurityFilterChain(HttpSecurity http) throws Exception {
    return http
        .exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint((request, response, exception) ->
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
            .accessDeniedHandler((request, response, exception) ->
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(SecurityConfig.publicMatchers()).permitAll()
            .anyRequest().authenticated())
        .addFilterBefore(new TestSecurityContextBridgeFilter(), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(
            new JwtAuthenticationFilter(SecurityConfig.publicMatchersList()),
            AuthorizationFilter.class)
        .build();
  }

  private static class TestSecurityContextBridgeFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
      var testAuthentication = TestSecurityContextHolder.getContext().getAuthentication();
      if (testAuthentication != null) {
        SecurityContextHolder.getContext().setAuthentication(testAuthentication);
      }
      filterChain.doFilter(request, response);
    }
  }
}
