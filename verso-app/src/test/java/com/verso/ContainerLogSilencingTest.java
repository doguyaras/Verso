package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.platform.core.handler.ContainerErrorLogSilencer;
import com.verso.support.WithVersoPostgres;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.catalina.Container;
import org.apache.catalina.Engine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Third-round review N1: the Tomcat host logger was silenced by a fixed name, "[Tomcat]", which only the first
 * embedded Tomcat in a JVM carries. The management server and any later application context ("Tomcat-1", ...) logged
 * filter exception messages with their stack trace again, and ContainerErrorPathTest passed only because of class
 * order. This test starts its own context (so its engines are never the first in a full test run) and checks every
 * server it starts by the engine's real name.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(ContainerLogSilencingTest.Recorder.class)
@WithVersoPostgres
class ContainerLogSilencingTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Recorder {

        static final List<Engine> ENGINES = new CopyOnWriteArrayList<>();

        @Bean
        static ApplicationListener<WebServerInitializedEvent> engineRecorder() {
            return event -> {
                if (event.getWebServer() instanceof TomcatWebServer tomcat) ENGINES.add(tomcat.getTomcat().getEngine());
            };
        }
    }

    @Autowired
    LoggingSystem loggingSystem;

    @Test
    void hostLoggers_whenApiAndManagementServersStart_areOffWhateverTheEngineName() {
        assertThat(Recorder.ENGINES).as("API and management servers").hasSize(2);
        for (Engine engine : Recorder.ENGINES) {
            Container[] hosts = engine.findChildren();
            assertThat(hosts).isNotEmpty();
            for (Container host : hosts) {
                String name = ContainerErrorLogSilencer.hostLoggerName(engine.getName(), host.getName());
                LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(name);
                assertThat(configuration).as(name).isNotNull();
                assertThat(configuration.getEffectiveLevel()).as(name).isEqualTo(LogLevel.OFF);
                // The dispatcher logger below the host inherits it (round-two B11 path).
                LoggerConfiguration dispatcher = loggingSystem.getLoggerConfiguration(name + ".[/].[dispatcherServlet]");
                assertThat(dispatcher == null ? LogLevel.OFF : dispatcher.getEffectiveLevel()).as(name).isEqualTo(LogLevel.OFF);
            }
        }
    }
}
