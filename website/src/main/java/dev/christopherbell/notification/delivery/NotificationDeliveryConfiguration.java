package dev.christopherbell.notification.delivery;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers typed notification delivery limits. */
@Configuration
@EnableConfigurationProperties(NotificationDeliveryProperties.class)
public class NotificationDeliveryConfiguration {}
