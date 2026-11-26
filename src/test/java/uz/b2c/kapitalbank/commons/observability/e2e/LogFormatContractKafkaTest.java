package uz.b2c.kapitalbank.commons.observability.e2e;

import org.junit.jupiter.api.Test;

/**
 * Контракт формата логов в режиме STDOUT_AND_KAFKA — так работает 1.x на проде. Брокер недоступен: проверяется
 * stdout, Kafka-аппендер не должен его менять. Подробности — {@link LogFormatContract}.
 */
class LogFormatContractKafkaTest extends LogFormatContract {

    @Test
    void stdoutInKafkaModeMatchesTheContract() throws Exception {
        checkContract("lib.observability.log.output=STDOUT_AND_KAFKA",
            "logging.appender.kafka.bootstrap.servers=127.0.0.1:1");
    }
}
