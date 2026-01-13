package com.test.obf;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;

/**
 * 난독화된 DatabaseHelper
 * 실제 이름: DatabaseHelper
 */
public class f {

    private static f f;

    private Connection g;

    private String h;

    private f() {
        h = "jdbc:sqlite:test.db";
        try {
            Class.forName("org.sqlite.JDBC");
            g = DriverManager.getConnection(h);
            i();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static f f() {
        if (f == null) {
            f = new f();
        }
        return f;
    }

    // [Deobfuscated] i -> initializePreferencesTable * creates a table for storing user preferences if it does not exist already
    private void initializePreferencesTable() {
        // createTables
        try {
            Statement statement = g.createStatement();
            String createTableQuery = "CREATE TABLE IF NOT EXISTS preferences (key TEXT PRIMARY KEY, value TEXT)";
            statement.execute(createTableQuery);
            statement.close();
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    public Map<String, String> l() {
        // loadFromDatabase
        Map<String, String> m = new HashMap<>();
        try {
            Statement n = g.createStatement();
            String o = "SELECT key, value FROM preferences";
            ResultSet p = n.executeQuery(o);
            while (p.next()) {
                m.put(p.getString("key"), p.getString("value"));
            }
            p.close();
            n.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return m;
    }

    // [Deobfuscated] m -> saveToDatabase * Saves key-value pair to a database
    public void saveToDatabase(String userKey, String userValue) {
        // saveToDatabase
        try {
            String insertQuery = "INSERT OR REPLACE INTO preferences (key, value) VALUES (?, ?)";
            PreparedStatement preparedStatement = g.prepareStatement(insertQuery);
            preparedStatement.setString(1, userKey);
            preparedStatement.setString(2, userValue);
            preparedStatement.executeUpdate();
            preparedStatement.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // [Deobfuscated] n -> getPreferenceValue * retrieves a preference value from the database based on the provided key
    public String getPreferenceValue(String preferenceKey) {
        // getValue
        try {
            String sqlQuery = "SELECT value FROM preferences WHERE key = ?";
            PreparedStatement preparedStatement = g.prepareStatement(sqlQuery);
            preparedStatement.setString(1, preferenceKey);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                String value = resultSet.getString("value");
                resultSet.close();
                preparedStatement.close();
                return value;
            }
            resultSet.close();
            preparedStatement.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // [Deobfuscated] o -> deletePreferenceByKey * deletes a preference from the database by its key
    public void deletePreferenceByKey(String key) {
        // deleteValue
        try {
            String sqlQuery = "DELETE FROM preferences WHERE key = ?";
            PreparedStatement preparedStatement = g.prepareStatement(sqlQuery);
            preparedStatement.setString(1, key);
            preparedStatement.executeUpdate();
            preparedStatement.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // [Deobfuscated] p -> clearPreferencesTable * clears the preferences table from a database
    public void clearPreferencesTable() {
        // clearAll
        try {
            Statement statement = g.createStatement();
            statement.execute("DELETE FROM preferences");
            statement.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // [Deobfuscated] q -> closeConnection * closes a connection if it is not null and has not been closed yet
    public void closeConnection() {
        // close
        try {
            if (g != null && !g.isClosed()) {
                g.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
