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
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class CbrClient {

    private static final DateTimeFormatter CBR_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private HttpClient client;

    public CbrClient() {
        client = HttpClient.newHttpClient();

        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        };

        SSLContext sslContext = null;
        try {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
        } catch (KeyManagementException | NoSuchAlgorithmException exception) {
            System.out.println("Error: " + exception.getMessage());
        }
        client = HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();

    }

    public String fetchRate(String currency, LocalDate date) throws Exception {

        String cbrDate = date.format(CBR_FORMAT);
        String url = "https://www.cbr.ru/scripts/XML_daily.asp?date_req=" + cbrDate;

        // соьираем http запрос
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        // читаем как поток байтов через ofInputStream() чтоб кириллица не ломалась
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        // парсим XML в DOM дерево
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(response.body());

        // достаём все элементы Valute
        NodeList valutes = doc.getElementsByTagName("Valute");

        for (int i = 0; i < valutes.getLength(); i++) {
            Element valute = (Element) valutes.item(i);

            String charCode = valute.getElementsByTagName("CharCode")
                    .item(0).getTextContent();

            if (charCode.equalsIgnoreCase(currency)) {
                return valute.getElementsByTagName("Value")
                        .item(0).getTextContent();
            }
        }
        throw new RuntimeException("Currency " + currency + " not found in CBR response");
    }
}
