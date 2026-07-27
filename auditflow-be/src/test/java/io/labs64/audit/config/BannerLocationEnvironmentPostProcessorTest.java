package io.labs64.audit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The banner is the smallest visible proof that the extension directory
 * (`/opt/labs64/ext`, via {@code PropertiesLauncher}) can change application
 * behaviour, so the seam that makes it overridable is worth pinning down.
 */
class BannerLocationEnvironmentPostProcessorTest {

    private final BannerLocationEnvironmentPostProcessor processor = new BannerLocationEnvironmentPostProcessor();

    @Test
    void defaultsTheBannerLocationWhenNobodyElseSetIt() {
        StandardEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(BannerLocationEnvironmentPostProcessor.BANNER_LOCATION_PROPERTY))
                .isEqualTo(BannerLocationEnvironmentPostProcessor.DEFAULT_BANNER_LOCATION);
    }

    @Test
    void yieldsToAnExplicitlyConfiguredLocation() {
        // This is the whole point: an extension jar on loader.path, an env var or a
        // config file must be able to replace the banner. A default that argues back
        // is not a default.
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of(BannerLocationEnvironmentPostProcessor.BANNER_LOCATION_PROPERTY, "classpath:ee-banner.txt")));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(BannerLocationEnvironmentPostProcessor.BANNER_LOCATION_PROPERTY))
                .isEqualTo("classpath:ee-banner.txt");
        assertThat(environment.getPropertySources()
                .contains(BannerLocationEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    void theDefaultLocationActuallyResolvesToAFile() {
        // Guards the constant against the banner being moved or renamed: a dangling
        // location would silently degrade to Spring's stock banner.
        String location = BannerLocationEnvironmentPostProcessor.DEFAULT_BANNER_LOCATION
                .replaceFirst("^classpath:", "");

        assertThat(new ClassPathResource(location).exists())
                .withFailMessage("Banner not found at %s — the default location no longer resolves.", location)
                .isTrue();
    }

    @Test
    void thisModuleShipsNoBannerAtTheClasspathRoot() throws IOException {
        // A banner.txt at the classpath root is picked up by convention, with no
        // property involved — which is exactly what an extension cannot override.
        // Keeping ours under labs64/ is what makes spring.banner.location the only
        // thing deciding.
        //
        // Note there IS a root banner.txt on the classpath: spring-cloud-stream ships
        // one. That is precisely why the location must be set explicitly rather than
        // left to convention — without this post-processor, a dependency's banner
        // would win. So assert only that *we* do not add one back.
        ClassPathResource rootBanner = new ClassPathResource("banner.txt");
        if (!rootBanner.exists()) {
            return;
        }
        assertThat(rootBanner.getURL().toString())
                .withFailMessage("This module ships banner.txt at the classpath root again (%s); the "
                        + "convention would override spring.banner.location and make the banner "
                        + "unextendable.", rootBanner.getURL())
                .doesNotContain("/auditflow-be/target/classes/");
    }

    @Test
    void isRegisteredUnderTheKeyBootActuallyReads() throws IOException {
        // Boot 4.1 discovers EnvironmentPostProcessor through META-INF/spring.factories
        // under the `org.springframework.boot.EnvironmentPostProcessor` key — NOT through
        // a META-INF/spring/....imports file, and NOT under the deprecated
        // `org.springframework.boot.env.` package name. Registering it any other way
        // fails silently: the processor simply never runs and the banner quietly
        // reverts to Spring's stock one.
        ClassPathResource factories = new ClassPathResource("META-INF/spring.factories");
        assertThat(factories.exists())
                .withFailMessage("META-INF/spring.factories is missing; the post-processor is not registered.")
                .isTrue();

        String contents = factories.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(contents).contains("org.springframework.boot.EnvironmentPostProcessor=");
        assertThat(contents).contains(BannerLocationEnvironmentPostProcessor.class.getName());
        assertThat(contents)
                .withFailMessage("Registered under the deprecated org.springframework.boot.env key, "
                        + "which Boot 4.1 does not read.")
                .doesNotContain("org.springframework.boot.env.EnvironmentPostProcessor=");
    }
}
