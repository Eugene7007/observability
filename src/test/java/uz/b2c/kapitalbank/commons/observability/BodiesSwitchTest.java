package uz.b2c.kapitalbank.commons.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Один переключатель тел для сервиса: LIB_OBSERVABILITY_HTTP_LOG_BODIES = OFF | ON_ERROR | ALWAYS.
 */
class BodiesSwitchTest {

    @Test
    void defaultsLogBodiesOfFailedExchangesOnAnyPath() {
        HttpLogSettings settings = new HttpLogSettings(new HttpLogProperties());

        assertThat(settings.logBodies("/api/v1/transfers")).isTrue();
        assertThat(settings.includeBodies(200, false)).isFalse();
        assertThat(settings.includeBodies(422, false)).isTrue();
        assertThat(settings.includeBodies(null, true)).isTrue();
    }

    @Test
    void offDisablesBodiesEvenForErrors() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setBodies(HttpLogProperties.Bodies.OFF);
        HttpLogSettings settings = new HttpLogSettings(properties);

        assertThat(settings.logBodies("/api/v1/transfers")).isFalse();
    }

    @Test
    void alwaysLogsBodiesOfSuccessfulExchanges() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setBodies(HttpLogProperties.Bodies.ALWAYS);
        HttpLogSettings settings = new HttpLogSettings(properties);

        assertThat(settings.logBodies("/api/v1/transfers")).isTrue();
        assertThat(settings.includeBodies(200, false)).isTrue();
    }

    @Test
    void excludedPathsStayWithoutBodies() {
        HttpLogSettings settings = new HttpLogSettings(new HttpLogProperties());

        assertThat(settings.isExcluded("/actuator/health")).isTrue();
    }
}
