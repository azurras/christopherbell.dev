package dev.christopherbell.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers validated shared-folder configuration without creating a filesystem operation bean. */
@Configuration
@EnableConfigurationProperties({SharedFolderProperties.class, SharedFolderMediaProperties.class})
public class SharedFolderConfiguration {}
