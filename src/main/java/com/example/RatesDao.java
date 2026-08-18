package com.example;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class RatesDao {

    private static final Logger logger = Logger.getLogger(RatesDao.class.getName());

    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;

    public RatesDao(String dbUrl, String dbUser, String dbPassword) {
        this.dbUrl = dbUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        logger.config("RatesDao initialized with URL: " + dbUrl);
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(dbUrl, dbUser, dbPassword);
    }

    public String[] findRate(String currency, LocalDate date) throws SQLException {

        String sql = "SELECT rate, cachedat FROM rates WHERE targetcurrency = ? AND date = ?";

        // try-with-resources: соединение и statement закроются автоматически
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, currency);
            ps.setObject(2, date);

            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                double rate = rs.getDouble("rate");
                String rateStr = String.valueOf(rate).replace('.', ',');

                String cachedAt = rs.getTimestamp("cachedat").toInstant().toString();

                return new String[]{rateStr, cachedAt};
            }
        }

        return null;
    }

    public void saveRate(String currency, LocalDate date, String rateWithComma) throws SQLException {

        String sql = "INSERT INTO rates (targetcurrency, date, rate, cachedat) VALUES (?, ?, ?, ?)";

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, currency);
            ps.setObject(2, date);

            ps.setDouble(3, Double.parseDouble(rateWithComma.replace(',', '.')));
            // сохраняем в текущий момент
            ps.setTimestamp(4, Timestamp.from(Instant.now()));

            ps.executeUpdate();  // INSERT
        }
    }

    public int deleteByDate(LocalDate date) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("DELETE FROM rates WHERE date = ?")) {
            ps.setObject(1, date);
            return ps.executeUpdate();
        }
    }

    public int deleteAll() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("DELETE FROM rates")) {
            return ps.executeUpdate();
        }
    }

    public void trimCache(int maxSize) throws SQLException {
        // считаем записи
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM rates");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            if (rs.getInt(1) <= maxSize) return;
        }
        // удаляем всё за самую старую дату
        String sql = "DELETE FROM rates WHERE date = " +
                "(SELECT date FROM rates ORDER BY cachedat ASC LIMIT 1)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    // все записи кэша для статистики
    public List<String[]> findAll() throws SQLException {
        List<String[]> result = new ArrayList<>();
        String sql = "SELECT targetcurrency, date, rate, cachedat FROM rates ORDER BY cachedat";

        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String cur = rs.getString("targetcurrency");
                String date = rs.getObject("date", LocalDate.class).toString(); // 2026-08-03
                String rate = String.valueOf(rs.getDouble("rate")).replace('.', ','); // 83,1259
                String cachedAt = rs.getTimestamp("cachedat").toInstant().toString();
                result.add(new String[]{cur, date, rate, cachedAt});
            }
        }
        return result;
    }
}