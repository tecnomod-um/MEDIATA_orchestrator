package org.taniwha.config;

import java.io.File;

interface LauncherCommandRunner {
    boolean dockerAvailable();

    boolean httpResponds(String url);

    String containerStatus(String name) throws Exception;

    int runAndLogIfFails(File dir, String... cmd) throws Exception;

    default void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
