package com.example;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;


public class CbrClient implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(CbrClient.class.getName());
    private static final DateTimeFormatter CBR_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // 1 клиент на всё время жизни объекта
    private final HttpClient client;

    public CbrClient() {
        this.client = createClient();
    }

    private static HttpClient createClient() {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        };
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return HttpClient.newBuilder().sslContext(sslContext).build();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "SSL init failed, using default client", e);
            return HttpClient.newHttpClient();
        }
    }

    // dозвращает курс валюты на дату
    public String fetchRate(String currency, LocalDate date) throws Exception {

        String cbrDate = date.format(CBR_FORMAT);
        String url = "https://www.cbr.ru/scripts/XML_daily.asp?date_req=" + cbrDate;

        logger.finer("CBR request URL: " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        // Читаем потоком: XML-парсер сам подхватит windows-1251 из заголовка
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new RuntimeException("CBR API returned status " + response.statusCode());
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(response.body());

        NodeList valutes = doc.getElementsByTagName("Valute");

        for (int i = 0; i < valutes.getLength(); i++) {
            Element valute = (Element) valutes.item(i);
            String charCode = valute.getElementsByTagName("CharCode").item(0).getTextContent();
            if (charCode.equalsIgnoreCase(currency)) {
                String rate = valute.getElementsByTagName("Value").item(0).getTextContent();
                logger.finer("CBR response: found " + currency + " = " + rate);
                return rate;
            }
        }
        throw new RuntimeException("Currency " + currency + " not found in CBR response");
    }

    // освобождам ресурсы
    @Override
    public void close() {
        client.close();
    }
}