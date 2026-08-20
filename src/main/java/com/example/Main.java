package com.example;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.logging.Logger;

public class Main {

    private static final Logger logger = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws IOException {
        logger.info("Application starting...");

        AppConfig config = AppConfig.load();

        // Клиенты к БД и к ЦБ РФ
        RatesDao dao = new RatesDao(config.getDbUrl(), config.getDbUser(), config.getDbPassword());
        CbrClient cbr = new CbrClient();

        // обработчики
        RateHandler rateHandler = new RateHandler(config, dao, cbr);
        CacheHandler cacheHandler = new CacheHandler(config, dao, cbr);

        URI uri = URI.create(config.getUrl());
        int port = uri.getPort();
        if (port == -1) port = 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/v1/rates", rateHandler::handle);
        server.createContext("/api/v1/cache/rates", cacheHandler::handle);
        server.start();
        logger.info("Server started on " + config.getUrl());

        // стоп сервера + закрытие HTTP-клиента
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Application shutting down...");
            server.stop(0);
            cbr.close();
        }));
    }
}