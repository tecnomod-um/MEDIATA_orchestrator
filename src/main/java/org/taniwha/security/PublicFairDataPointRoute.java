package org.taniwha.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

public final class PublicFairDataPointRoute {
    private static final String SEGMENT = "[A-Za-z0-9_-][A-Za-z0-9_.-]*";
    private static final Pattern METADATA = Pattern.compile("/taniwha/fdp(?:/?|/spec|/catalog/?"
            + "|/catalog/" + SEGMENT + "(?:/dataset/?|/spec)?"
            + "|/dataset/" + SEGMENT + "(?:/distribution/?|/spec)?"
            + "|/distribution/" + SEGMENT + "(?:/spec)?"
            + "|/(?:profile|metadata-schemas|shapes)/" + SEGMENT + ")");
    private static final Pattern PROXY = Pattern.compile("^(?:/taniwha)?/nodes/proxy/[^/]+(/taniwha/fdp.*)$");

    private PublicFairDataPointRoute() {}

    public static boolean matches(HttpServletRequest request) {
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        if (path.isEmpty()) path = request.getRequestURI();
        var match = PROXY.matcher(path);
        return match.matches() && allows(request.getMethod(), match.group(1));
    }

    public static boolean allows(String method, String downstreamPath) {
        return ("GET".equals(method) || "OPTIONS".equals(method)) && METADATA.matcher(downstreamPath).matches();
    }
}
