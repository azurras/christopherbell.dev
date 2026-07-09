package dev.christopherbell.configuration;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for resolving client IP addresses from trusted forwarding proxies.
 */
@ConfigurationProperties(prefix = "client-ip")
@Data
public class ClientIpProperties {
  private List<String> trustedProxies = new ArrayList<>();
}
