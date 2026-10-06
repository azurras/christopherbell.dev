package dev.christopherbell.photo;

import dev.christopherbell.photo.model.PhotoProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the typed photo gallery settings. */
@Configuration
@EnableConfigurationProperties(PhotoProperties.class)
public class PhotoConfiguration {
}
