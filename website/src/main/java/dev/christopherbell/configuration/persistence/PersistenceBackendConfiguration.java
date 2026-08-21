package dev.christopherbell.configuration.persistence;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Activates strict binding for the sole persistence backend selector. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersistenceBackendProperties.class)
public class PersistenceBackendConfiguration {}
