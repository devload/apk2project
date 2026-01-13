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

    private void i() {
        // createTables
        try {
            Statement j = g.createStatement();
            String k = "CREATE TABLE IF NOT EXISTS preferences (key TEXT PRIMARY KEY, value TEXT)";
            j.execute(k);
            j.close();
        } catch (Exception e) {
            e.printStackTrace();
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

    public void m(String key, String value) {
        // saveToDatabase
        try {
            String q = "INSERT OR REPLACE INTO preferences (key, value) VALUES (?, ?)";
            PreparedStatement r = g.prepareStatement(q);
            r.setString(1, key);
            r.setString(2, value);
            r.executeUpdate();
            r.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String n(String key) {
        // getValue
        try {
            String s = "SELECT value FROM preferences WHERE key = ?";
            PreparedStatement t = g.prepareStatement(s);
            t.setString(1, key);
            ResultSet u = t.executeQuery();
            if (u.next()) {
                String v = u.getString("value");
                u.close();
                t.close();
                return v;
            }
            u.close();
            t.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public void o(String key) {
        // deleteValue
        try {
            String p = "DELETE FROM preferences WHERE key = ?";
            PreparedStatement q = g.prepareStatement(p);
            q.setString(1, key);
            q.executeUpdate();
            q.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void p() {
        // clearAll
        try {
            Statement r = g.createStatement();
            r.execute("DELETE FROM preferences");
            r.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void q() {
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
