package com.verso;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.modulith.Modulithic;

/**
 * Verso's single deployable (shape A, ADR-0001). The domain modules live in direct sub-packages of this one.
 *
 * <p>This is {@code @SpringBootApplication} spelled out for one reason: the platform starters
 * ({@code com.verso.platform..}) sit under the same root package but must be loaded only through
 * AutoConfiguration.imports, never by component scan (reference 4.5). Scanning them would register their
 * components twice and make {@code @ConditionalOnMissingBean} depend on scan order.
 */
@Modulithic
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.verso\\.platform\\..*")})
public class VersoApp {

    public static void main(String[] args) {
        SpringApplication.run(VersoApp.class, args);
    }
}
