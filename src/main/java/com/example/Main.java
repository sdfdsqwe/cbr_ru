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

import java.util.logging.Logger;
import java.util.logging.Level;

public class Main {

    private static final Logger logger = Logger.getLogger(Main.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RatesDao dao;
    private static CbrClient cbr;

    // запасные значения по умолчанию
    private static int cacheSize = 100;
    private static long cacheTtlSeconds = 3600;

    public static void main(String[] args) throws IOException {

        // Логируем запуск приложения
        logger.info("Application starting...");

        // читаем конф файл
        Properties props = new Properties();
        String configFile = System.getProperty("config.file");

        if (configFile != null) {
            try (FileInputStream in = new FileInputStream(configFile)) {
                props.load(in);
                logger.config("Configuration loaded from: " + configFile);

                // Логируем все параметры конфига
                for (String key : props.stringPropertyNames()) {
                    String value = key.contains("password") ? "***" : props.getProperty(key);
                    logger.config(key + " = " + value);
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to load config file", e);
            }
        } else {
            logger.warning("No config.file specified. Using defaults.");
        }

        String url = props.getProperty("url", "http://localhost:8080/");
        String dbUrl = props.getProperty("db.url", "jdbc:postgresql://localhost:5432/postgres");
        String dbUser = props.getProperty("db.user", "postgres");
        String dbPassword = props.getProperty("db.password", "postgres");

        // параметры кэша
        String cacheSizeStr = props.getProperty("cache.size");
        String cacheTtlStr = props.getProperty("cache.ttl");

        if (cacheSizeStr == null) {
            throw new RuntimeException("Missing required config parameter: cache.size");
        }
        if (cacheTtlStr == null) {
            throw new RuntimeException("Missing required config parameter: cache.ttl");
        }

        try {
            cacheSize = Integer.parseInt(cacheSizeStr);
            cacheTtlSeconds = Long.parseLong(cacheTtlStr);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid cache params: cache.size and cache.ttl must be numbers", e);
        }

        logger.config("Cache size: " + cacheSize + ", TTL: " + cacheTtlSeconds + " sec");

        // создаём клиентов к БД и к ЦБ РФ
        dao = new RatesDao(dbUrl, dbUser, dbPassword);
        cbr = new CbrClient();

        // запускаем HTTP сервер
        URI uri = URI.create(url);
        int port = uri.getPort();
        if (port == -1) port = 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/v1/rates", Main::handleRequest);
        server.createContext("/api/v1/cache/rates", Main::handleCache);
        server.start();

        logger.info("Server started on " + url);

        // Обработчик остановки приложения
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Application shutting down...");
            server.stop(0);
        }));
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {

        // получение запроса
        logger.fine("Received request: " + exchange.getRequestMethod() + " " + exchange.getRequestURI());

        // содержимое запроса
        String query = exchange.getRequestURI().getQuery();
        logger.finer("Request query: " + query);

        Map<String, String> params = parseQueryParams(query);
        String currency = params.get("targetCurrency");
        String dateStr = params.get("date");

        if (currency == null || currency.isBlank() || dateStr == null || dateStr.isBlank()) {
            logger.warning("Missing parameters: targetCurrency=" + currency + ", date=" + dateStr);
            sendJson(exchange, 400, Map.of(
                    "error", "missing_parameters",
                    "message", "targetCurrency and date are required"));
            return;
        }

        LocalDate date;
        try {
            date = LocalDate.parse(dateStr);
        } catch (Exception e) {
            logger.warning("Invalid date format: " + dateStr);
            sendJson(exchange, 400, Map.of(
                    "error", "invalid_date",
                    "message", "date must be in YYYY-MM-DD format"));
            return;
        }

        String cur = currency.toUpperCase();
        String[] cached = null;
        String rate = null;

        String cachedAt = null;

        try {
            cached = dao.findRate(cur, date); // пытаемся прочитать с бд
        } catch (SQLException e) {
            logger.log(Level.WARNING, "DB unavailable on findRate, skipping cache", e);
            cached = null; // если БД упала - считаем кэш пустым
        }

        // проверка TTL
        if (cached != null && isExpired(cached[1])) {
            try {

                dao.deleteByDate(date);
            } catch (SQLException e) {
                logger.log(Level.WARNING, "DB unavailable on deleteByDate (TTL)", e);
            }
            cached = null;
            logger.info("Cache EXPIRED (TTL): " + cur + " / " + date);
        }

        if (cached != null) {
            // берем из кеша
            rate = cached[0];
            cachedAt = cached[1];
            logger.info("Cache HIT (DB): " + cur + " / " + date);
        } else {
            //идем в цб
            try {
                rate = cbr.fetchRate(cur, date);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "CBR API unavailable", e);
                sendJson(exchange, 502, Map.of(
                        "error", "cbr_unavailable",
                        "message", String.valueOf(e.getMessage())));
                return;
            }

            try {
                dao.saveRate(cur, date, rate);
            } catch (SQLException e) {
                logger.log(Level.WARNING, "DB unavailable on saveRate, not caching", e);
            }

            try {
                dao.trimCache(cacheSize);
            } catch (SQLException e) {
                logger.log(Level.WARNING, "DB unavailable on trimCache", e);
            }

            cachedAt = Instant.now().toString();
            logger.info("Cache MISS -> fetched from CBR: " + cur + " / " + date);
        }

        // собираем JSON ответ
        Map<String, String> response = new LinkedHashMap<>();
        response.put("target_currency", cur);
        response.put("date", dateStr);
        response.put("rate", rate);
        response.put("cached_at", cachedAt);

        // содержимое ответа
        logger.finer("Response: " + response);

        sendJson(exchange, 200, response);
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
        logger.fine("Received DELETE request: " + exchange.getRequestURI());

        try {
            String dateStr = parseQueryParams(exchange.getRequestURI().getQuery()).get("date");

            int deleted = (dateStr == null || dateStr.isBlank())
                    ? dao.deleteAll()
                    : dao.deleteByDate(LocalDate.parse(dateStr));

            logger.info("Cache DELETE: " + deleted + " rows (date=" + dateStr + ")");
            sendJson(exchange, 200, Map.of("status", "ok", "deleted", deleted));

        } catch (SQLException e) {
            logger.log(Level.WARNING, "DB unavailable on cache delete", e);
            sendJson(exchange, 503, Map.of(
                    "error", "db_unavailable",
                    "message", "Cannot clear cache: database is unavailable"));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unexpected error in cache delete", e);
            sendJson(exchange, 500, Map.of("error", "internal_error",
                    "message", String.valueOf(e.getMessage())));
        }
    }

    private static void handleCacheStats(HttpExchange exchange) throws IOException {
        logger.fine("Received stats request: " + exchange.getRequestURI());

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

            logger.finer("Stats response: " + stats);
            sendJson(exchange, 200, stats);

        } catch (SQLException e) {
            logger.log(Level.WARNING, "DB unavailable on cache stats", e);
            sendJson(exchange, 503, Map.of(
                    "error", "db_unavailable",
                    "message", "Cannot show cache stats: database is unavailable"));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unexpected error in cache stats", e);
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

// java --% -Djava.util.logging.config.file=logging.properties -Dconfig.file=config.properties -jar target/cbr_ru-1.0.0.jar
// curl -X DELETE "http://localhost:8080/api/v1/rates?targetCurrency=USD&date=2026-08-14"
// http://localhost:8080/api/v1/cache/rates статистика кеша
// http://localhost:8080/api/v1/rates?targetCurrency=USD&date=2026-08-06 курс
