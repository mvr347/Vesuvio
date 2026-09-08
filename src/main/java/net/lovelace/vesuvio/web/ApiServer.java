package net.lovelace.vesuvio.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.lovelace.vesuvio.check.onnx.MLManager;
import net.lovelace.vesuvio.check.selflearning.ActiveLearning;
import net.lovelace.vesuvio.check.selflearning.SelfLearningManager;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.storage.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import static net.lovelace.vesuvio.config.ConfigManager.DEFAULT_WEB_BEARER_TOKEN;

/**
 * High-performance REST API and Web Dashboard server powered by Java 21 Virtual Threads.
 * Zero external servlet bloat, sub-millisecond response times.
 *
 * Author: Lovelace
 */
public final class ApiServer implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-Web");

    private final Plugin plugin;
    private final ConfigManager config;
    private final UserDataManager userDataManager;
    private final MLManager mlManager;
    private final SelfLearningManager selfLearning;
    private final DatabaseManager databaseManager;

    private HttpServer server;
    private byte[] cachedDashboardHtml;

    public ApiServer(Plugin plugin,
                     ConfigManager config,
                     UserDataManager userDataManager,
                     MLManager mlManager,
                     SelfLearningManager selfLearning,
                     DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.config = config;
        this.userDataManager = userDataManager;
        this.mlManager = mlManager;
        this.selfLearning = selfLearning;
        this.databaseManager = databaseManager;

        loadDashboardHtml();
    }

    private void loadDashboardHtml() {
        try (InputStream in = plugin.getResource("web/dashboard.html")) {
            if (in != null) {
                cachedDashboardHtml = in.readAllBytes();
            } else {
                cachedDashboardHtml = "<h1>Vesuvio Dashboard</h1><p>Resource not found.</p>".getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to load dashboard.html resource", e);
            cachedDashboardHtml = "<h1>Vesuvio Dashboard</h1>".getBytes(StandardCharsets.UTF_8);
        }
    }

    public void start() {
        if (!config.isWebEnabled()) {
            LOGGER.info("[Vesuvio] Web server is disabled in config.yml.");
            return;
        }

        boolean usingDefaultToken = DEFAULT_WEB_BEARER_TOKEN.equals(config.getWebBearerToken());
        boolean publiclyBound = !"127.0.0.1".equals(config.getWebHost()) && !"localhost".equals(config.getWebHost());
        if (usingDefaultToken && publiclyBound) {
            LOGGER.severe("=============================================================================");
            LOGGER.severe("[Vesuvio] SECURITY WARNING: web.bearer-token is still the default value and");
            LOGGER.severe("[Vesuvio] web.host is bound publicly (" + config.getWebHost() + "). Anyone who can reach this");
            LOGGER.severe("[Vesuvio] port can reset VL/Risk and hot-reload models. Change web.bearer-token");
            LOGGER.severe("[Vesuvio] in config.yml immediately, or set web.host to 127.0.0.1 if the panel is");
            LOGGER.severe("[Vesuvio] only accessed locally / via a reverse proxy that adds its own auth.");
            LOGGER.severe("=============================================================================");
        }

        try {
            int port = config.getWebPort();
            String host = config.getWebHost();
            server = HttpServer.create(new InetSocketAddress(host, port), 0);

            // Virtual Threads executor with plugin classloader
            ClassLoader pluginClassLoader = getClass().getClassLoader();
            server.setExecutor(Executors.newThreadPerTaskExecutor(task -> {
                Thread t = Thread.ofVirtual().name("Vesuvio-API-", 0).unstarted(task);
                t.setContextClassLoader(pluginClassLoader);
                return t;
            }));

            // Root dashboard
            server.createContext("/", this::handleRoot);

            // API routes
            server.createContext("/api/v1/overview", this::handleOverview);
            server.createContext("/api/v1/suspects", this::handleSuspects);
            server.createContext("/api/v1/player/", this::handlePlayerRoute);
            server.createContext("/api/v1/punishments", this::handlePunishments);
            server.createContext("/api/v1/reviews/pending", this::handlePendingReviews);
            server.createContext("/api/v1/review/", this::handleReviewAction);
            server.createContext("/api/v1/models/reload", this::handleModelsReload);

            server.start();
            LOGGER.info(String.format("[Vesuvio] Web Dashboard & REST API listening at http://%s:%d (Virtual Threads Enabled)", host, port));

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to start Vesuvio Web Server", e);
        }
    }

    private boolean checkAuth(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null) return false;
        String expected = "Bearer " + config.getWebBearerToken();

        byte[] a = authHeader.trim().getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        // Constant-time comparison to avoid a timing side-channel on the bearer token.
        return MessageDigest.isEqual(a, b);
    }

    /**
     * Reads the request body up to a hard cap to prevent an unauthenticated-adjacent handler
     * from being used for memory-exhaustion DoS against the embedded HTTP server.
     */
    private static final int MAX_REQUEST_BODY_BYTES = 16 * 1024;

    private String readBoundedBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            int total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_REQUEST_BODY_BYTES) {
                    throw new IOException("Request body exceeds " + MAX_REQUEST_BODY_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private void sendCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
    }

    private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        sendCors(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendCors(exchange);
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path) || "/index.html".equals(path)) {
            sendCors(exchange);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, cachedDashboardHtml.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(cachedDashboardHtml);
            }
        } else {
            exchange.sendResponseHeaders(404, -1);
        }
    }

    private void handleOverview(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendCors(exchange);
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!checkAuth(exchange)) {
            sendJson(exchange, 401, "{\"error\": \"Unauthorized\"}");
            return;
        }

        double tps = 20.0;
        try {
            double[] tpsArray = Bukkit.getTPS();
            if (tpsArray.length > 0) tps = tpsArray[0];
        } catch (Throwable ignored) {}

        int onlineCount = Bukkit.getOnlinePlayers().size();
        int suspectsCount = userDataManager.getSuspects(config.getHighRiskThreshold()).size();
        int totalFlags = databaseManager.getTotalFlagsCount();
        long mlQueries = mlManager.getTotalInferences();

        String json = String.format(Locale.US,
                "{\"status\":\"online\",\"tps\":%.2f,\"onlineCount\":%d,\"suspectsCount\":%d,\"totalFlags\":%d,\"mlQueries\":%d}",
                tps, onlineCount, suspectsCount, totalFlags, mlQueries);

        sendJson(exchange, 200, json);
    }

    private void handleSuspects(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendCors(exchange);
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!checkAuth(exchange)) {
            sendJson(exchange, 401, "{\"error\": \"Unauthorized\"}");
            return;
        }

        List<UserData> suspects = userDataManager.getSuspects(20.0);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < suspects.size(); i++) {
            UserData u = suspects.get(i);
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.US,
                    "{\"uuid\":\"%s\",\"name\":\"%s\",\"risk\":%.1f,\"trust\":%.1f,\"vl\":%.1f,\"brand\":\"%s\",\"check\":\"%s\"}",
                    u.getUuid(), u.getUsername(), u.getRiskIndex(), u.getTrustScore(), u.getVl(), u.getClientBrand(), u.getLastTriggeredCheck()));
        }
        sb.append("]");

        sendJson(exchange, 200, sb.toString());
    }

    private void handlePlayerRoute(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendCors(exchange);
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        // Example: /api/v1/player/{uuid}/score or /action
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length < 5) {
            sendJson(exchange, 404, "{\"error\": \"Not found\"}");
            return;
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(parts[4]);
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, "{\"error\": \"Invalid UUID\"}");
            return;
        }

        String subRoute = (parts.length >= 6) ? parts[5] : "";

        // GET /api/v1/player/{uuid}/score (Public or authenticated)
        if ("score".equalsIgnoreCase(subRoute)) {
            UserData data = userDataManager.get(uuid);
            if (data == null) {
                sendJson(exchange, 404, "{\"error\": \"Player not tracked\"}");
                return;
            }
            String json = String.format(Locale.US,
                    "{\"uuid\":\"%s\",\"trust\":%.2f,\"risk\":%.2f,\"vl\":%.2f,\"brand\":\"%s\"}",
                    uuid, data.getTrustScore(), data.getRiskIndex(), data.getVl(), data.getClientBrand());
            sendJson(exchange, 200, json);
            return;
        }

        if (!checkAuth(exchange)) {
            sendJson(exchange, 401, "{\"error\": \"Unauthorized\"}");
            return;
        }

        // POST /api/v1/player/{uuid}/action
        if ("action".equalsIgnoreCase(subRoute) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            UserData data = userDataManager.get(uuid);
            if (data != null) {
                data.resetVl();
                data.adjustRisk(-30.0);
            }
            sendJson(exchange, 200, "{\"status\": \"Action executed\"}");
            return;
        }

        sendJson(exchange, 404, "{\"error\": \"Unknown player sub-route\"}");
    }

    private void handlePunishments(HttpExchange exchange) throws IOException {
        if (!checkAuth(exchange)) {
            sendJson(exchange, 401, "{\"error\": \"Unauthorized\"}");
            return;
        }
        sendJson(exchange, 200, "[]");
    }

    private void handlePendingReviews(HttpExchange exchange) throws IOException {
        if (!checkAuth(exchange)) {
            sendJson(exchange, 401, "{\"error\": \"Unauthorized\"}");
            return;
        }

        Collection<ActiveLearning.ReviewSample> samples = selfLearning.getActiveLearning().getPendingReviews();
        StringBuilder sb = new StringBuilder("[");
        int idx = 0;
        for (ActiveLearning.ReviewSample s : samples) {
            if (idx++ > 0) sb.append(",");
            float[] f = s.features();
            sb.append(String.format(Locale.US,
                    "{\"id\":\"%s\",\"uuid\":\"%s\",\"playerName\":\"%s\",\"probability\":%.3f,\"meanDelay\":%.1f,\"stdDev\":%.1f,\"duplicateRatio\":%.2f}",
                    s.id(), s.playerUuid(), s.playerName(), s.probability(),
                    f.length > 0 ? f[0] : 0f,
                    f.length > 1 ? f[1] : 0f,
                    f.length > 4 ? f[4] : 0f));
        }
        sb.append("]");

        sendJson(exchange, 200, sb.toString());
    }

    private void handleReviewAction(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\": \"Method not allowed\"}");
            return;
        }
        if (!checkAuth(exchange)) {
            sendJson(exchange, 401, "{\"error\": \"Unauthorized\"}");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length < 5) {
            sendJson(exchange, 400, "{\"error\": \"Missing sample ID\"}");
            return;
        }

        String sampleId = parts[4];
        String body;
        try {
            body = readBoundedBody(exchange);
        } catch (IOException e) {
            sendJson(exchange, 413, "{\"error\": \"Request body too large\"}");
            return;
        }
        String verdict = body.contains("cheat") ? "cheat" : "legit";

        ActiveLearning.ReviewSample sample = selfLearning.getActiveLearning().getSample(sampleId);
        UserData uData = (sample != null) ? userDataManager.get(sample.playerUuid()) : null;

        boolean success = selfLearning.getActiveLearning().submitVerdict(
                sampleId, verdict, "WebAdmin",
                selfLearning.getOnlineClassifier(),
                selfLearning.getDatasetManager(),
                uData
        );

        if (success) {
            sendJson(exchange, 200, "{\"status\": \"Verdict accepted\"}");
        } else {
            sendJson(exchange, 404, "{\"error\": \"Sample not found or expired\"}");
        }
    }

    private void handleModelsReload(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\": \"Method not allowed\"}");
            return;
        }
        if (!checkAuth(exchange)) {
            sendJson(exchange, 401, "{\"error\": \"Unauthorized\"}");
            return;
        }

        Path modelsDir = plugin.getDataFolder().toPath().resolve("models");
        mlManager.hotReload("click_model", modelsDir.resolve("click_model.onnx"));
        mlManager.hotReload("aim_model", modelsDir.resolve("aim_model.onnx"));

        sendJson(exchange, 200, "{\"status\": \"Hot-reload dispatched\"}");
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(1);
            LOGGER.info("[Vesuvio] Web server stopped.");
        }
    }
}
