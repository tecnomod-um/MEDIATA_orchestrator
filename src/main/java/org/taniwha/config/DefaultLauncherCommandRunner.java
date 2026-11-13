package org.taniwha.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

class DefaultLauncherCommandRunner implements LauncherCommandRunner {
    private static final Logger logger = LoggerFactory.getLogger(DefaultLauncherCommandRunner.class);

    @Override
    public boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean httpResponds(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1000);
            c.setReadTimeout(2000);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    @Override
    public String containerStatus(String name) throws Exception {
        Process p = new ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", name)
                .redirectErrorStream(true)
                .start();
        String out;
        try (Scanner s = new Scanner(p.getInputStream(), StandardCharsets.UTF_8).useDelimiter("\\A")) {
            out = s.hasNext() ? s.next().trim() : "";
        }
        p.waitFor();
        if (p.exitValue() != 0) {
            return "notfound";
        }
        if ("true".equalsIgnoreCase(out)) {
            return "running";
        }
        return "exited";
    }

    @Override
    public int runAndLogIfFails(File dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (dir != null) {
            pb.directory(dir);
        }

        Process p = pb.start();
        String out;
        try (Scanner s = new Scanner(p.getInputStream(), StandardCharsets.UTF_8).useDelimiter("\\A")) {
            out = s.hasNext() ? s.next() : "";
        }
        p.waitFor();

        int rc = p.exitValue();
        if (rc != 0) {
            logger.error("Command failed (rc={}): {}\n{}", rc, join(cmd), out);
        } else {
            logger.debug("Command ok: {}", join(cmd));
        }
        return rc;
    }

    private String join(String[] cmd) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cmd.length; i++) {
            if (i > 0) {
                b.append(' ');
            }
            b.append(cmd[i]);
        }
        return b.toString();
    }
}
