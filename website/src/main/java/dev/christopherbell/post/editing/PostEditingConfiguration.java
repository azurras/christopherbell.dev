package dev.christopherbell.post.editing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers validated post editing policy. */
@Configuration
@EnableConfigurationProperties(PostEditingProperties.class)
public class PostEditingConfiguration {}
