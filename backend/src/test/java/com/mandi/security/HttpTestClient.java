package com.mandi.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;

/**
 * A browser stand-in for the security tests: real HTTP to the embedded server, with a hand-made cookie jar. The jar sends every
 * cookie it holds whatever the Secure attribute says (a real browser would not send a Secure cookie over http), so the same flows
 * run under both the development-style and the production-like cookie settings, and the raw Set-Cookie headers can be inspected.
 */
final class HttpTestClient {

    record Response(int status, String body, Map<String, List<String>> headers, List<String> setCookies) {
        String header(String name) {
            List<String> v = headers.get(name.toLowerCase());
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        <T> T json(String path) {
            return JsonPath.read(body, path);
        }
    }

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base;
    final Map<String, String> jar = new LinkedHashMap<>();

    HttpTestClient(int port) {
        this.base = "http://127.0.0.1:" + port;
    }

    Response get(String path, String... headers) {
        return send("GET", path, null, headers);
    }

    Response post(String path, String json, String... headers) {
        return send("POST", path, json == null ? "" : json, headers);
    }

    /** headers are name, value pairs */
    Response send(String method, String path, String body, String... headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (body != null) b.header("Content-Type", "application/json");
            if (!jar.isEmpty()) {
                StringBuilder cookie = new StringBuilder();
                jar.forEach((k, v) -> cookie.append(cookie.length() == 0 ? "" : "; ").append(k).append('=').append(v));
                b.header("Cookie", cookie.toString());
            }
            for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            List<String> setCookies = new ArrayList<>(r.headers().allValues("set-cookie"));
            for (String sc : setCookies) absorb(sc);
            return new Response(r.statusCode(), r.body(), r.headers().map(), setCookies);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private void absorb(String setCookie) {
        String[] parts = setCookie.split(";");
        int eq = parts[0].indexOf('=');
        String name = parts[0].substring(0, eq).trim();
        String value = parts[0].substring(eq + 1).trim();
        boolean expired = value.isEmpty();
        for (String attr : parts) {
            String a = attr.trim().toLowerCase();
            if (a.startsWith("max-age=") && a.substring(8).trim().startsWith("0")) expired = true;
        }
        if (expired) jar.remove(name); else jar.put(name, value);
    }

    // ---- the usual steps -----------------------------------------------------------------------------------------

    /** GET /api/v1/auth/csrf; returns the token (also in the jar as the CSRF cookie). */
    String csrf() {
        Response r = get("/api/v1/auth/csrf");
        if (r.status() != 200) throw new IllegalStateException("csrf endpoint answered " + r.status());
        return r.json("$.token");
    }

    Response login(String email, String password, String csrfToken) {
        String body = "{\"email\":" + quote(email) + ",\"password\":" + quote(password) + "}";
        return post("/api/v1/auth/login", body, "X-XSRF-TOKEN", csrfToken);
    }

    /** csrf() then login(); returns the response of the login. */
    Response signIn(String email, String password) {
        return login(email, password, csrf());
    }

    String csrfCookie(String cookieName) {
        return jar.get(cookieName);
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
