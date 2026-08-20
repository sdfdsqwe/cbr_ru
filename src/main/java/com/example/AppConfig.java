package com.example;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * читает файл из системного свойства config.file;
 * для отсутствующих или некорректных параметров берёт дефолты.
 */
public class AppConfig {

    private static final Logger logger = Logger.getLogger(AppConfig.class.getName());

    // Значения по умолчанию
    private static final String DEFAULT_URL = "http://localhost:8080/";
    private static final String DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/postgres";
    private static final String DEFAULT_DB_USER = "postgres";
    private static final String DEFAULT_DB_PASSWORD = "postgres";
    private static final int DEFAULT_CACHE_SIZE = 100;
    private static final long DEFAULT_CACHE_TTL_SECONDS = 3600;

    private final String url;
    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final int cacheSize;
    private final long cacheTtlSeconds;

    private AppConfig(Properties props) {
        this.url = props.getProperty("url", DEFAULT_URL);
        this.dbUrl = props.getProperty("db.url", DEFAULT_DB_URL);
        this.dbUser = props.getProperty("db.user", DEFAULT_DB_USER);
        this.dbPassword = props.getProperty("db.password", DEFAULT_DB_PASSWORD);
        this.cacheSize = parseInt(props.getProperty("cache.size"), DEFAULT_CACHE_SIZE);
        this.cacheTtlSeconds = parseLong(props.getProperty("cache.ttl"), DEFAULT_CACHE_TTL_SECONDS);
    }

    // загружает конфигурацию и возвращает готовый объект AppConfig
    public static AppConfig load() {
        Properties props = new Properties();
        String configFile = System.getProperty("config.file");

        if (configFile != null) {
            try (FileInputStream in = new FileInputStream(configFile)) {
                props.load(in);
                logger.config("Configuration loaded from: " + configFile);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to load config file, using defaults", e);
            }
        } else {
            logger.config("No config.file specified, using defaults");
        }

        AppConfig config = new AppConfig(props);
        config.logParameters();
        return config;
    }

    // dывод параметров на уровне CONFIGmvn clean package
    private void logParameters() {
        logger.config("url = " + url);
        logger.config("db.url = " + dbUrl);
        logger.config("db.user = " + dbUser);
        logger.config("db.password = ***");
        logger.config("cache.size = " + cacheSize);
        logger.config("cache.ttl = " + cacheTtlSeconds);
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warning("Bad int value '" + value + "', using default " + defaultValue);
            return defaultValue;
        }
    }

    private static long parseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            logger.warning("Bad long value '" + value + "', using default " + defaultValue);
            return defaultValue;
        }
    }

    // Доступ к параметрам — через геттеры
    public String getUrl() { return url; }
    public String getDbUrl() { return dbUrl; }
    public String getDbUser() { return dbUser; }
    public String getDbPassword() { return dbPassword; }
    public int getCacheSize() { return cacheSize; }
    public long getCacheTtlSeconds() { return cacheTtlSeconds; }
}