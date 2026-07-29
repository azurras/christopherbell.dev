package dev.christopherbell.post.abuse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers validated new-account Void mutation limits. */
@Configuration
@EnableConfigurationProperties(NewAccountVoidMutationLimitProperties.class)
public class NewAccountVoidMutationConfiguration {}
