package uz.b2c.kapitalbank.commons.observability;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тела у ошибок пишутся всегда; переключатель LIB_OBSERVABILITY_HTTP_LOG_BODIES = ON_ERROR | ALWAYS управляет только
 * телами успешных обменов. Лимит тела ограничен снизу и сверху.
 */
class BodiesSwitchTest {

    private static final String PATH = "/api/v1/transfers";

    @Test
    void defaultsLogBodiesOnlyForErrors() {
        HttpLogSettings settings = new HttpLogSettings(new HttpLogProperties());

        assertThat(settings.includeBodies(200, false, PATH)).isFalse();
        assertThat(settings.includeBodies(302, false, PATH)).isFalse();
        assertThat(settings.includeBodies(400, false, PATH)).isTrue();
        assertThat(settings.includeBodies(422, false, PATH)).isTrue();
        assertThat(settings.includeBodies(503, false, PATH)).isTrue();
        assertThat(settings.includeBodies(null, true, PATH)).isTrue();
    }

    @Test
    void offIsNoLongerAValue() {
        assertThat(HttpLogProperties.Bodies.values())
            .containsExactly(HttpLogProperties.Bodies.ON_ERROR, HttpLogProperties.Bodies.ALWAYS);
    }

    @Test
    void alwaysLogsBodiesOfSuccessfulExchanges() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setBodies(HttpLogProperties.Bodies.ALWAYS);
        HttpLogSettings settings = new HttpLogSettings(properties);

        assertThat(settings.includeBodies(200, false, PATH)).isTrue();
    }

    @Test
    void bodyPathsNarrowOnlySuccessfulExchanges() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setBodies(HttpLogProperties.Bodies.ALWAYS);
        properties.setBodyPaths(List.of("/api/v1/payments/**"));
        HttpLogSettings settings = new HttpLogSettings(properties);

        assertThat(settings.includeBodies(200, false, PATH)).isFalse();
        assertThat(settings.includeBodies(200, false, "/api/v1/payments/1")).isTrue();
        assertThat(settings.includeBodies(500, false, PATH)).isTrue();
    }

    @Test
    void bodyLimitIsBounded() {
        HttpLogProperties properties = new HttpLogProperties();
        assertThat(new HttpLogSettings(properties).maxBodyBytes()).isEqualTo(4096);
        properties.setMaxBodyBytes(10 * 1024 * 1024);
        assertThat(new HttpLogSettings(properties).maxBodyBytes()).isEqualTo(HttpLogProperties.MAX_BODY_BYTES);
        properties.setMaxBodyBytes(0);
        assertThat(new HttpLogSettings(properties).maxBodyBytes()).isEqualTo(HttpLogProperties.MIN_BODY_BYTES);
        properties.setMaxBodyBytes(32 * 1024);
        assertThat(new HttpLogSettings(properties).maxBodyBytes()).isEqualTo(32 * 1024);
    }

    @Test
    void excludedPathsStayWithoutBodies() {
        HttpLogSettings settings = new HttpLogSettings(new HttpLogProperties());

        assertThat(settings.isExcluded("/actuator/health")).isTrue();
    }
}
