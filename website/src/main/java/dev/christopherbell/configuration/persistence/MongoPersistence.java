package dev.christopherbell.configuration.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** Marks a transition-only MongoDB persistence adapter. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "backend", havingValue = "mongodb")
public @interface MongoPersistence {}
