package com.example;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

// обработчик /api/v1/rates, курс с учётом кэша, TTL и размера
public class RateHandler {

    private static final Logger logger = Logger.getLogger(RateHandler.class.getName());

    private final AppConfig config;
    private final RatesDao dao;
    private final CbrClient cbr;

    public RateHandler(AppConfig config, RatesDao dao, CbrClient cbr) {
        this.config = config;
        this.dao = dao;
        this.cbr = cbr;
    }

    public void handle(HttpExchange exchange) throws IOException {
        logger.fine("Received request: " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
        logger.finer("Request query: " + exchange.getRequestURI().getQuery());

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpUtils.sendJson(exchange, 405, Map.of("error", "method_not_allowed", "message", "Use GET"));
            return;
        }

        Map<String, String> params = HttpUtils.parseQueryParams(exchange.getRequestURI().getQuery());
        String currency = params.get("targetCurrency");
        String dateStr = params.get("date");

        if (currency == null || currency.isBlank() || dateStr == null || dateStr.isBlank()) {
            HttpUtils.sendJson(exchange, 400, Map.of("error", "missing_parameters",
                    "message", "targetCurrency and date are required"));
            return;
        }

        LocalDate date;
        try {
            date = LocalDate.parse(dateStr);
        } catch (Exception e) {
            HttpUtils.sendJson(exchange, 400, Map.of("error", "invalid_date",
                    "message", "date must be in YYYY-MM-DD format"));
            return;
        }

        String cur = currency.toUpperCase();
        String[] cached = null;
        String rate;
        String cachedAt;

        // ищем в кэше, если БД упала считаем кэш пустым
        try {
            cached = dao.findRate(cur, date);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "DB unavailable on findRate, skipping cache", e);
        }

        // протухло - удаляем и идём в ЦБ
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
            rate = cached[0];
            cachedAt = cached[1];
            logger.info("Cache HIT (DB): " + cur + " / " + date);
        } else {
            try {
                rate = cbr.fetchRate(cur, date);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "CBR API unavailable", e);
                HttpUtils.sendJson(exchange, 502, Map.of("error", "cbr_unavailable",
                        "message", String.valueOf(e.getMessage())));
                return;
            }
            try {
                dao.saveRate(cur, date, rate);
                dao.trimCache(config.getCacheSize());
            } catch (SQLException e) {
                logger.log(Level.WARNING, "DB unavailable on saveRate/trimCache, not caching", e);
            }
            cachedAt = Instant.now().toString();
            logger.info("Cache MISS -> fetched from CBR: " + cur + " / " + date);
        }

        Map<String, String> response = new LinkedHashMap<>();
        response.put("target_currency", cur);
        response.put("date", dateStr);
        response.put("rate", rate);
        response.put("cached_at", cachedAt);

        logger.finer("Response: " + response);
        HttpUtils.sendJson(exchange, 200, response);
    }

    // потухла ли запись
    private boolean isExpired(String cachedAt) {
        long age = Instant.now().getEpochSecond() - Instant.parse(cachedAt).getEpochSecond();
        return age > config.getCacheTtlSeconds();
    }
}