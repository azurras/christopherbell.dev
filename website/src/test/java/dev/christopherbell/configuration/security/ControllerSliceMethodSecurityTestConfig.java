package dev.christopherbell.configuration.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

@TestConfiguration
@EnableWebSecurity
@EnableMethodSecurity
public class ControllerSliceMethodSecurityTestConfig extends ControllerSliceSecurityTestConfig {
}
