package com.javaee.aiservice.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.javaee.common.exception.BusinessException;
import com.javaee.common.exception.GlobalExceptionHandler;
import com.javaee.common.model.Result;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerSafeLoggingTest {

    @Test
    void commonHandlerPreservesResponseButNeverLogsExceptionMessageOrThrowable() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logger.detachAndStopAllAppenders();
        logger.setLevel(Level.ALL);
        logger.setAdditive(false);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        String marker = "jdbc:mysql://runtime-marker:3306/db?password=marker body=marker";

        Result<Void> response = handler.handleBusinessException(new BusinessException(49001, marker));

        assertThat(response.getCode()).isEqualTo(49001);
        assertThat(response.getMessage()).isEqualTo(marker);
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getFormattedMessage())
                .contains("event=GLOBAL_API_BUSINESS_REJECTED")
                .contains("reasonCode=BUSINESS_RULE_REJECTED")
                .contains("exceptionCategory=VALIDATION")
                .contains("retryable=false retryCount=0 count=")
                .doesNotContain(marker, "jdbc:mysql", "password=", "body=");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void commonUnexpectedHandlerLogsNoThrowableStack() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logger.detachAndStopAllAppenders();
        logger.setLevel(Level.ALL);
        logger.setAdditive(false);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        handler.handleException(new IllegalStateException("sensitive-runtime-marker"));

        ILoggingEvent event = appender.list.get(appender.list.size() - 1);
        assertThat(event.getFormattedMessage())
                .contains("event=GLOBAL_API_FAILED")
                .contains("reasonCode=INTERNAL_ERROR")
                .doesNotContain("sensitive-runtime-marker");
        assertThat(event.getThrowableProxy()).isNull();
    }
}
