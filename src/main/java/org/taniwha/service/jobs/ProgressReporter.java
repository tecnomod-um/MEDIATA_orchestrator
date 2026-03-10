package org.taniwha.service.jobs;

@FunctionalInterface
public interface ProgressReporter {
    void report(int percent, String message);
    static ProgressReporter noop() {
        return (p, m) -> {};
    }
}
