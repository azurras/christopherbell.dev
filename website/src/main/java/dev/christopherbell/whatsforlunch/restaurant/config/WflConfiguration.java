package dev.christopherbell.whatsforlunch.restaurant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables validated What's For Lunch configuration binding. */
@Configuration
@EnableConfigurationProperties(WflProperties.class)
public class WflConfiguration {}
