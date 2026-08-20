package com.example;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

// обработчик /api/v1/cache/rates: GET/DELETE
public class CacheHandler {

    private static final Logger logger = Logger.getLogger(CacheHandler.class.getName());

    private final AppConfig config;
    private final RatesDao dao;
    private final CbrClient cbr;

    public CacheHandler(AppConfig config, RatesDao dao, CbrClient cbr) {
        this.config = config;
        this.dao = dao;
        this.cbr = cbr;
    }

    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        logger.fine("Received request: " + method + " " + exchange.getRequestURI());

        if ("GET".equalsIgnoreCase(method)) {
            handleStats(exchange);
        } else if ("DELETE".equalsIgnoreCase(method)) {
            handleDelete(exchange);
        } else {
            HttpUtils.sendJson(exchange, 405, Map.of("error", "method_not_allowed",
                    "message", "Use GET or DELETE"));
        }
    }

    private void handleStats(HttpExchange exchange) throws IOException {
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
            stats.put("cache_ttl", String.valueOf(config.getCacheTtlSeconds()));
            stats.put("cache_size", rows.size());
            stats.put("cache_size_max", config.getCacheSize());
            stats.put("rates", rates);

            logger.finer("Stats response: " + stats);
            HttpUtils.sendJson(exchange, 200, stats);

        } catch (SQLException e) {
            logger.log(Level.WARNING, "DB unavailable on cache stats", e);
            HttpUtils.sendJson(exchange, 503, Map.of("error", "db_unavailable",
                    "message", "Cannot show cache stats: database is unavailable"));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unexpected error in cache stats", e);
            HttpUtils.sendJson(exchange, 500, Map.of("error", "internal_error",
                    "message", String.valueOf(e.getMessage())));
        }
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        try {
            String dateStr = HttpUtils.parseQueryParams(exchange.getRequestURI().getQuery()).get("date");

            int deleted = (dateStr == null || dateStr.isBlank())
                    ? dao.deleteAll()
                    : dao.deleteByDate(LocalDate.parse(dateStr));

            logger.info("Cache DELETE: " + deleted + " rows (date=" + dateStr + ")");
            HttpUtils.sendJson(exchange, 200, Map.of("status", "ok", "deleted", deleted));

        } catch (SQLException e) {
            logger.log(Level.WARNING, "DB unavailable on cache delete", e);
            HttpUtils.sendJson(exchange, 503, Map.of("error", "db_unavailable",
                    "message", "Cannot clear cache: database is unavailable"));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unexpected error in cache delete", e);
            HttpUtils.sendJson(exchange, 500, Map.of("error", "internal_error",
                    "message", String.valueOf(e.getMessage())));
        }
    }
}