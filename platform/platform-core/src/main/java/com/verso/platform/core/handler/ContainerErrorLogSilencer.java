package com.verso.platform.core.handler;

import org.apache.catalina.Container;
import org.apache.catalina.Engine;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Turns off Tomcat's host logger tree for every embedded Tomcat this application starts (API and management port).
 * That tree logs an exception thrown by a filter, and a failure during the error dispatch, with its message and stack
 * trace; EnvelopeErrorController already logs the same failure with types only (reference 8.4; reviews B11, B20).
 *
 * <p>The logger name carries the engine name, which is "Tomcat" only for the first server in the JVM; the next ones
 * are "Tomcat-1", "Tomcat-2" (the management server, a second application context in tests). A fixed name in
 * application.yml therefore missed them (third-round review N1). This listener reads the real engine name once the
 * server has started; events of the management child context reach it through the parent context.
 */
public class ContainerErrorLogSilencer implements ApplicationListener<WebServerInitializedEvent> {

    static final String CONTAINER_LOGGER = "org.apache.catalina.core.ContainerBase";

    private final LoggingSystem loggingSystem;

    public ContainerErrorLogSilencer(LoggingSystem loggingSystem) {
        this.loggingSystem = loggingSystem;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (!(event.getWebServer() instanceof TomcatWebServer tomcat)) return;
        Engine engine = tomcat.getTomcat().getEngine();
        for (Container host : engine.findChildren()) {
            loggingSystem.setLogLevel(hostLoggerName(engine.getName(), host.getName()), LogLevel.OFF);
        }
    }

    /** Same format as Tomcat's ContainerBase.getLogName(): ContainerBase.[engine].[host]. */
    public static String hostLoggerName(String engineName, String hostName) {
        return CONTAINER_LOGGER + ".[" + engineName + "].[" + hostName + "]";
    }
}
