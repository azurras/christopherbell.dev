package dev.christopherbell.configuration.security;

import dev.christopherbell.configuration.ClientIpProperties;
import dev.christopherbell.configuration.ClientIpResolver;
import dev.christopherbell.configuration.RateLimitProperties;
import dev.christopherbell.configuration.filter.RateLimitFilter;
import dev.christopherbell.configuration.filter.RequestSizeLimitFilter;
import dev.christopherbell.libs.api.APIVersion;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Spring Security configuration.
 *
 * <p>Defines public routes, enables method security, and wires custom filters
 * for JWT auth, rate limiting, and request size limits.</p>
 */
@Configuration
@EnableMethodSecurity
@EnableWebSecurity
@EnableConfigurationProperties({ClientIpProperties.class, RateLimitProperties.class})
public class SecurityConfig {

  private static final String[] PUBLIC_URLS = {
      "/",
      "/api/accounts" + APIVersion.V20241215 + "/login",
      "/api/accounts" + APIVersion.V20241215 + "/create",
      "/api/accounts" + APIVersion.V20241215 + "/password-reset/request",
      "/api/accounts" + APIVersion.V20241215 + "/password-reset/confirm",
      "GET:/api/accounts" + APIVersion.V20250914 + "/profile/**",
      "/favicon.ico",
      "/profile",
      "/canes-box-tracker",
      "/canes-box-tracker/**",
      "/vin-decoder",
      "/zip-coordinates",
      // Public read-only post APIs (method-scoped)
      "GET:/api/canes-box-tracker" + APIVersion.V20260604 + "/history",
      "POST:/api/vehicles" + APIVersion.V20260509 + "/vin/decode",
      "GET:/api/location/zip/**",
      "GET:/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/today",
      "GET:/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/nearby",
      "GET:/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/nearby/zip/**",
      "GET:/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/profile/**",
      "GET:/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/preferences",
      "GET:/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/top-rated",
      "GET:/api/posts" + APIVersion.V20250914 + "/feed",
      "GET:/api/posts" + APIVersion.V20250914 + "/user/**",
      "GET:/api/posts" + APIVersion.V20250914 + "/*/thread",
      "/u/**",
      "/p/**",
      "/blog",
      "/css/**",
      "/images/**",
      "/js/**",
      "/login",
      "/forgot-password",
      "/reset-password",
      "/messages",
      "/notifications",
      "/photos",
      "/photos/**",
      "/report",
      "/signup",
      "/thebell/**",
      "/void",
      "/void/**",
      "/back-office",
      "/wfl",
      "/wfl/favorites",
      "/wfl/top-rated",
      "/wfl/restaurants/**"
  };

  /**
   * Builds the application {@link SecurityFilterChain}.
   *
   * @return the configured security filter chain
   */
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http,
      RateLimitFilter rateLimitFilter,
      JwtAuthenticationFilter jwtAuthenticationFilter,
      RequestSizeLimitFilter requestSizeLimitFilter) throws Exception {
    return http
        // Disable CSRF for APIs (use with care)
        .csrf(AbstractHttpConfigurer::disable)

        // Configure authorization rules
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(publicMatchers()).permitAll() // Allow public access to defined URLs
            .anyRequest().authenticated() // Secure all other endpoints
        )

        // Add rate limiting and JWT authentication filters
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
        .addFilterBefore(requestSizeLimitFilter, RateLimitFilter.class)
        
        // Build the SecurityFilterChain
        .build();
  }

  /**
   * Configures the rate limiting filter bean.
   */
  @Bean
  public RateLimitFilter rateLimitFilter(
      ClientIpResolver clientIpResolver,
      RateLimitProperties rateLimitProperties
  ) {
    return new RateLimitFilter(clientIpResolver, rateLimitProperties);
  }

  /**
   * Configures the trusted forwarding header client IP resolver.
   */
  @Bean
  public ClientIpResolver clientIpResolver(ClientIpProperties clientIpProperties) {
    return new ClientIpResolver(clientIpProperties);
  }

  /**
   * Configures the JWT authentication filter bean.
   */
  @Bean
  public JwtAuthenticationFilter jwtAuthenticationFilter() {
    return new JwtAuthenticationFilter(publicMatchersList());
  }

  /**
   * Configures the request size limiting filter bean.
   */
  @Bean
  public RequestSizeLimitFilter requestSizeLimitFilter() {
    return new RequestSizeLimitFilter();
  }

  /**
   * Exposes the Spring {@link AuthenticationManager}.
   */
  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
    return configuration.getAuthenticationManager();
  }

  /**
   * Helper to convert path patterns into {@link AntPathRequestMatcher}s.
   */
  private List<RequestMatcher> publicMatchersList() {
    List<RequestMatcher> matchers = Arrays.stream(PUBLIC_URLS)
        .map(Sec::toMatcher)
        .collect(Collectors.toList());
    // Add a precise matcher for single post GET: /api/posts/{version}/{postId}
    // Excludes reserved paths like "/me" and "/account/**".
    matchers.add(request -> {
      if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
      String prefix = "/api/posts" + APIVersion.V20250914 + "/";
      String path = request.getRequestURI();
      if (!path.startsWith(prefix)) return false;
      String tail = path.substring(prefix.length());
      if (tail.isEmpty()) return false;
      if (tail.contains("/")) return false; // only single segment
      if ("me".equals(tail)) return false;
      if (tail.startsWith("account")) return false;
      return true; // treat as public single-post GET
    });
    return matchers;
  }

  private RequestMatcher[] publicMatchers() {
    return publicMatchersList().toArray(new RequestMatcher[0]);
  }

  private static class Sec {
    static RequestMatcher toMatcher(String spec) {
      // Allow "METHOD:/path" or just "/path"
      if (spec.contains(":")) {
        String[] parts = spec.split(":", 2);
        String method = parts[0];
        String pattern = parts[1];
        return new AntPathRequestMatcher(pattern, method);
      }
      return new AntPathRequestMatcher(spec);
    }
  }
}
