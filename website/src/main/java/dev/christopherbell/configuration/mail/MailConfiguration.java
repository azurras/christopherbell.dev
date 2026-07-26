package dev.christopherbell.configuration.mail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers typed mail delivery settings. */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfiguration {
}
