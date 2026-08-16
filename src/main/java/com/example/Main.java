package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.sql.SQLException;

public class Main {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RatesDao dao;
    private static CbrClient cbr;

    // запасные значения по умолчанию
    private static int cacheSize = 100;
    private static long cacheTtlSeconds = 3600;

    public static void main(String[] args) throws IOException {

        // читаем конф файл
        Properties props = new Properties();
        String configFile = System.getProperty("config.file");

        if (configFile != null) {
            try (FileInputStream in = new FileInputStream(configFile)) {
                props.load(in);
                System.out.println("Configuration loaded from: " + configFile);
            } catch (Exception e) {
                System.err.println("Failed to load config file: " + e.getMessage());
            }
        } else {
            System.out.println("No config.file specified. Using defaults.");
        }

        // параметры бд
        String url = props.getProperty("url", "http://localhost:8080/");
        String dbUrl = props.getProperty("db.url", "jdbc:postgresql://localhost:5432/postgres");
        String dbUser = props.getProperty("db.user", "postgres");
        String dbPassword = props.getProperty("db.password", "postgres");

        // параметры кэша
        try {
            cacheSize = Integer.parseInt(props.getProperty("cache.size", "100"));
            cacheTtlSeconds = Long.parseLong(props.getProperty("cache.ttl", "3600"));
        } catch (Exception e) {
            System.err.println("Bad cache params, using defaults");
        }
        System.out.println("Cache size: " + cacheSize + ", TTL: " + cacheTtlSeconds + " sec");

        dao = new RatesDao(dbUrl, dbUser, dbPassword);
        cbr = new CbrClient();

        URI uri = URI.create(url);
        int port = uri.getPort();
        if (port == -1) port = 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/v1/rates", Main::handleRequest);
        server.createContext("/api/v1/cache/rates", Main::handleCache);
        server.start();
        System.out.println("Server started on " + url);
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
        String currency = params.get("targetCurrency");
        String dateStr = params.get("date");

        if (currency == null || currency.isBlank() || dateStr == null || dateStr.isBlank()) {
            sendJson(exchange, 400, Map.of(
                    "error", "missing_parameters",
                    "message", "targetCurrency and date are required"));
            return;
        }

        LocalDate date;
        try {
            date = LocalDate.parse(dateStr);
        } catch (Exception e) {
            sendJson(exchange, 400, Map.of(
                    "error", "invalid_date",
                    "message", "date must be in YYYY-MM-DD format"));
            return;
        }

        try {
            String cur = currency.toUpperCase();
            String[] cached = null;
            String rate = null;
            String cachedAt = null;

            // ищем в кэше если БД доступна
            try {
                cached = dao.findRate(cur, date);
            } catch (SQLException e) {
                System.err.println("DB unavailable on findRate, skipping cache: " + e.getMessage());
                cached = null;  // считаем кэш пустым
            }

            if (cached != null && isExpired(cached[1])) {
                try {
                    dao.deleteByDate(date);
                } catch (SQLException e) {
                    System.err.println("DB unavailable on deleteByDate (TTL): " + e.getMessage());
                }
                cached = null;
                System.out.println("Cache EXPIRED (TTL): " + cur + " / " + date);
            }

            if (cached != null) {
                // берём из кэша
                rate = cached[0];
                cachedAt = cached[1];
                System.out.println("Cache HIT (DB): " + cur + " / " + date);
            } else {
                // идём в ЦБ
                try {
                    rate = cbr.fetchRate(cur, date);
                } catch (Exception e) {
                    // если и цб не досьупна
                    sendJson(exchange, 502, Map.of(
                            "error", "cbr_unavailable",
                            "message", String.valueOf(e.getMessage())));
                    return;
                }

                // сохраняем в БД
                try {
                    dao.saveRate(cur, date, rate);
                } catch (SQLException e) {
                    System.err.println("DB unavailable on saveRate, not caching: " + e.getMessage());
                }

                // контроль размера
                try {
                    dao.trimCache(cacheSize);
                } catch (SQLException e) {
                    System.err.println("DB unavailable on trimCache: " + e.getMessage());
                }

                cachedAt = Instant.now().toString();
                System.out.println("Cache MISS -> fetched from CBR: " + cur + " / " + date);
            }

            // === ШАГ 4: собираем JSON ответ ===
            Map<String, String> response = new LinkedHashMap<>();
            response.put("target_currency", cur);
            response.put("date", dateStr);
            response.put("rate", rate);
            response.put("cached_at", cachedAt);

            sendJson(exchange, 200, response);

        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, 500, Map.of("error", "internal_error", "message", String.valueOf(e.getMessage())));
        }
    }

    // диспетчер get/delete
    private static void handleCache(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if ("GET".equalsIgnoreCase(method)) {
            handleCacheStats(exchange);
        } else if ("DELETE".equalsIgnoreCase(method)) {
            handleCacheDelete(exchange);
        } else {
            sendJson(exchange, 405, Map.of(
                    "error", "method_not_allowed",
                    "message", "Use GET or DELETE"));
        }
    }

    private static void handleCacheDelete(HttpExchange exchange) throws IOException {
        try {
            String dateStr = parseQueryParams(exchange.getRequestURI().getQuery()).get("date");

            int deleted = (dateStr == null || dateStr.isBlank())
                    ? dao.deleteAll()
                    : dao.deleteByDate(LocalDate.parse(dateStr));

            System.out.println("Cache DELETE: " + deleted + " rows (date=" + dateStr + ")");
            sendJson(exchange, 200, Map.of("status", "ok", "deleted", deleted));

        } catch (SQLException e) {
            // БД недоступна - возвращаем ошибку
            System.err.println("DB unavailable on cache delete: " + e.getMessage());
            sendJson(exchange, 503, Map.of(
                    "error", "db_unavailable",
                    "message", "Cannot clear cache: database is unavailable"));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, 500, Map.of("error", "internal_error",
                    "message", String.valueOf(e.getMessage())));
        }
    }

    private static void handleCacheStats(HttpExchange exchange) throws IOException {
        try {
            List<String[]> rows = dao.findAll();

            List<Map<String, String>> rates = new ArrayList<>();
            for (String[] r : rows) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("target_currency", r[0]);
                item.put("date", r[1]);
                item.put("rate", r[2]);
                item.put("cached_at", r[3]);
                rates.add(item);
            }

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("cache_kind", "rates");
            stats.put("cache_ttl", String.valueOf(cacheTtlSeconds));
            stats.put("cache_size", rows.size());
            stats.put("cache_size_max", cacheSize);
            stats.put("rates", rates);

            sendJson(exchange, 200, stats);

        } catch (SQLException e) {
            // БД недоступна - ошибка
            System.err.println("DB unavailable on cache stats: " + e.getMessage());
            sendJson(exchange, 503, Map.of(
                    "error", "db_unavailable",
                    "message", "Cannot show cache stats: database is unavailable"));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, 500, Map.of("error", "internal_error",
                    "message", String.valueOf(e.getMessage())));
        }
    }

    private static boolean isExpired(String cachedAt) {
        long age = Instant.now().getEpochSecond() - Instant.parse(cachedAt).getEpochSecond();
        return age > cacheTtlSeconds;
    }

    private static void sendJson(HttpExchange exchange, int statusCode, Map<?, ?> data) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return params;

        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }
}

// java -Dconfig.file=config.properties -jar target/cbr_ru-1.0.0.jar
// http://localhost:8080/api/v1/cache/rates статистика кеша
// http://localhost:8080/api/v1/rates?targetCurrency=USD&date=2026-08-06 курс
