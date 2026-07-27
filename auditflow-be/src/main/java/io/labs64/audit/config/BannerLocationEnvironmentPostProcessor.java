package io.labs64.audit.config;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Points {@code spring.banner.location} at AuditFlow's banner, as an ordinary
 * property with the lowest possible precedence.
 *
 * <p>A {@code banner.txt} sitting at the classpath root is picked up by
 * convention, with no property involved — and a convention cannot be overridden
 * by anything added to the classpath later. Since the application now launches
 * through {@code PropertiesLauncher} with an extension directory
 * ({@code LOADER_PATH=/opt/labs64/ext}), an extension that wants to say
 * something different at startup needs a seam that behaves like configuration.
 * Making the location an explicit, defaulted property is that seam: any
 * higher-precedence source — an environment variable, a config file, an
 * extension's own {@code EnvironmentPostProcessor} — simply wins.
 *
 * <p>The banner file itself moved off the classpath root
 * ({@code labs64/auditflow-banner.txt}) precisely so the convention no longer
 * applies and this property is the only thing deciding.
 *
 * <p>This is a plain CE capability with no knowledge of who might use it.
 */
public class BannerLocationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String BANNER_LOCATION_PROPERTY = "spring.banner.location";
    static final String DEFAULT_BANNER_LOCATION = "classpath:labs64/auditflow-banner.txt";
    static final String PROPERTY_SOURCE_NAME = "auditflowBannerDefaults";

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment,
            final SpringApplication application) {
        if (environment.containsProperty(BANNER_LOCATION_PROPERTY)) {
            // Someone already had an opinion; defaults do not argue with it.
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME,
                Map.of(BANNER_LOCATION_PROPERTY, DEFAULT_BANNER_LOCATION)));
    }

    @Override
    public int getOrder() {
        // Last: every other post-processor gets to set the property first.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
