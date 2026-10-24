package com.verso.support;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * On GitHub Actions only, writes one {@code ::error} workflow command per failed test, so the failure shows as an
 * annotation of the check run (readable next to the job without opening the full log). The message is the assertion
 * text, cut to 1 000 characters; tests use synthetic data and per-run random secrets only.
 */
public final class GithubAnnotations implements TestExecutionListener {

    private static final boolean ENABLED = "true".equals(System.getenv("GITHUB_ACTIONS"));

    @Override
    public void executionFinished(TestIdentifier test, TestExecutionResult result) {
        if (!ENABLED || result.getStatus() == TestExecutionResult.Status.SUCCESSFUL) return;
        String name = test.getSource().filter(MethodSource.class::isInstance).map(MethodSource.class::cast)
                .map(m -> m.getClassName().substring(m.getClassName().lastIndexOf('.') + 1) + "#" + m.getMethodName())
                .orElse(test.getDisplayName());
        String message = result.getThrowable().map(t -> t.getClass().getSimpleName() + ": " + t.getMessage())
                .orElse(result.getStatus().name());
        if (message.length() > 1000) message = message.substring(0, 1000);
        System.out.println("::error title=" + data(name).replace(":", "%3A").replace(",", "%2C") + "::" + data(message));
    }

    private static String data(String value) {
        return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }
}
