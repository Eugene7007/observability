package uz.b2c.kapitalbank.commons.observability.e2e;

import org.junit.jupiter.api.Test;

/** Контракт формата логов в режиме STDOUT (целевой режим, умолчание с 2.0). Подробности — {@link LogFormatContract}. */
class LogFormatContractTest extends LogFormatContract {

    @Test
    void stdoutMatchesTheContract() throws Exception {
        checkContract("lib.observability.log.output=STDOUT");
    }
}
