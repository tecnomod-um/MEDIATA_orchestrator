package org.taniwha.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.*;

@Configuration
public class AsyncConfig {

    @Bean(name = "llmExecutor", destroyMethod = "shutdown")
    public ExecutorService llmExecutor() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        int queueSize = 500;

        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueSize),
                namedDaemonFactory("llm-exec-"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean(name = "enrichExecutor", destroyMethod = "shutdown")
    public ExecutorService enrichExecutor() {
        int threads = 2;
        int queueSize = 100;

        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueSize),
                namedDaemonFactory("enrich-exec-"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private ThreadFactory namedDaemonFactory(String prefix) {
        return r -> {
            Thread t = new Thread(r);
            t.setName(prefix + t.getId());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((th, ex) ->
                    System.err.println("Uncaught in " + th.getName() + ": " + ex));
            return t;
        };
    }
}
