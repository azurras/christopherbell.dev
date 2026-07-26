package dev.christopherbell.configuration.security;

import dev.christopherbell.configuration.ClientIpProperties;
import dev.christopherbell.configuration.ClientIpResolver;
import dev.christopherbell.configuration.RateLimitProperties;
import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.configuration.filter.RateLimitFilter;
import dev.christopherbell.configuration.filter.RequestSizeLimitFilter;
import dev.christopherbell.libs.api.APIVersion;
import dev.christopherbell.sharedfolder.web.SharedFolderNoStoreFilter;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
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
@EnableConfigurationProperties({
    BrowserSecurityProperties.class, ClientIpProperties.class, RateLimitProperties.class,
    SharedFolderProperties.class})
public class SecurityConfig {

  private static final String CONTENT_SECURITY_POLICY = String.join("; ",
      "default-src 'self'",
      "base-uri 'self'",
      "object-src 'none'",
      "script-src 'self' https://cdn.jsdelivr.net",
      "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://maxcdn.bootstrapcdn.com",
      "font-src 'self' data: https://maxcdn.bootstrapcdn.com",
      "img-src 'self' data: blob: https:",
      "connect-src 'self' https://gateway.raisingcanes.com https://order.raisingcanes.com",
      "frame-src https://www.youtube.com https://www.youtube-nocookie.com https://open.spotify.com https://w.soundcloud.com",
      "frame-ancestors 'self'",
      "media-src 'self' blob:",
      "worker-src 'self' blob:",
      "form-action 'self'");
  private static final String PERMISSIONS_POLICY =
      "camera=(), geolocation=(), microphone=(), payment=(), usb=()";

  private static final String[] PUBLIC_URLS = {
      "/",
      "GET:/robots.txt",
      "GET:/sitemap.xml",
      "GET:/actuator/health/liveness",
      "GET:/actuator/health/readiness",
      "/shared",
      "GET:/shared-folder-auth-sw.js",
      "/api/accounts" + APIVersion.V20241215 + "/login",
      "/api/accounts" + APIVersion.V20241215 + "/logout",
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
      "GET:/{assetVersion}/css/**",
      "GET:/{assetVersion}/images/**",
      "GET:/{assetVersion}/js/**",
      "GET:/{assetVersion}/favicon.ico",
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
      "/command-center",
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
      BrowserSecurityProperties browserSecurityProperties,
      RateLimitFilter rateLimitFilter,
      JwtAuthenticationFilter jwtAuthenticationFilter,
      RequestSizeLimitFilter requestSizeLimitFilter,
      SharedFolderNoStoreFilter sharedFolderNoStoreFilter) throws Exception {
    return http
        .csrf(csrf -> csrf
            .spa()
            .ignoringRequestMatchers(SecurityConfig::hasExplicitBearerToken))

        .headers(headers -> {
          headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY));
          headers.frameOptions(frameOptions -> frameOptions.sameOrigin());
          headers.httpStrictTransportSecurity(hsts -> hsts
              .requestMatcher(request -> browserSecurityProperties.hstsEnabled())
              .includeSubDomains(true)
              .maxAgeInSeconds(31_536_000));
          headers.addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY));
          headers.referrerPolicy(referrer -> referrer
              .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
        })

        // Configure authorization rules
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(publicMatchers()).permitAll() // Allow public access to defined URLs
            .anyRequest().authenticated() // Secure all other endpoints
        )

        // Add rate limiting and JWT authentication filters
        .addFilterBefore(jwtAuthenticationFilter, AuthorizationFilter.class)
        .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
        .addFilterBefore(requestSizeLimitFilter, RateLimitFilter.class)
        .addFilterBefore(sharedFolderNoStoreFilter, CsrfFilter.class)
        
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

  private static boolean hasExplicitBearerToken(jakarta.servlet.http.HttpServletRequest request) {
    var authorization = request.getHeader(org.springframework.http.HttpHeaders.AUTHORIZATION);
    return authorization != null
        && authorization.startsWith("Bearer ")
        && !authorization.substring("Bearer ".length()).isBlank();
  }

  /**
   * Configures the request size limiting filter bean.
   */
  @Bean
  public RequestSizeLimitFilter requestSizeLimitFilter(SharedFolderProperties sharedFolderProperties) {
    return new RequestSizeLimitFilter(1_000_000L, sharedFolderProperties.uploadChunk().toBytes());
  }

  /** Applies no-store headers before authentication can return a protected shared-folder error. */
  @Bean
  public SharedFolderNoStoreFilter sharedFolderNoStoreFilter(
      ObjectProvider<SharedFolderAuditRecorder> auditRecorder) {
    SharedFolderAuditRecorder recorder = auditRecorder.getIfAvailable();
    return recorder == null
        ? new SharedFolderNoStoreFilter()
        : new SharedFolderNoStoreFilter(recorder);
  }

  /**
   * Exposes the Spring {@link AuthenticationManager}.
   */
  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
    return configuration.getAuthenticationManager();
  }

  /**
   * Helper to convert path patterns into {@link PathPatternRequestMatcher}s.
   */
  public static List<RequestMatcher> publicMatchersList() {
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

  public static RequestMatcher[] publicMatchers() {
    return publicMatchersList().toArray(new RequestMatcher[0]);
  }

  private static class Sec {
    static RequestMatcher toMatcher(String spec) {
      // Allow "METHOD:/path" or just "/path"
      if (spec.contains(":")) {
        String[] parts = spec.split(":", 2);
        String method = parts[0];
        String pattern = parts[1];
        return PathPatternRequestMatcher.pathPattern(HttpMethod.valueOf(method), pattern);
      }
      return PathPatternRequestMatcher.pathPattern(spec);
    }
  }
}
