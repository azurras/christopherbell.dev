package dev.christopherbell.blog;

import dev.christopherbell.blog.model.BlogProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the typed blog content settings. */
@Configuration
@EnableConfigurationProperties(BlogProperties.class)
public class BlogConfiguration {
}
