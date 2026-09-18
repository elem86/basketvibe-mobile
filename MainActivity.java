package com.elem86.basketvibemobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String DB_FILE_NAME = "basketvibe_mobile.sqlite";
    private static final String ASSET_DB_FILE_NAME = "basketvibe_mobile.sqlite";
    private static final int REQUEST_OPEN_DATABASE = 1001;

    private WebView webView;
    private final Object dbLock = new Object();
    private SQLiteDatabase database;
    private File databaseFile;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(8, 13, 24));
        getWindow().setNavigationBarColor(Color.rgb(8, 13, 24));

        databaseFile = new File(getFilesDir(), DB_FILE_NAME);
        ensureSeedDatabase();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(8, 13, 24));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new BasketVibeBridge(this), "BasketVibe");
        webView.loadUrl("file:///android_asset/index.html");

        setContentView(webView);
    }

    private void ensureSeedDatabase() {
        if (databaseFile.exists() && databaseFile.length() > 0) {
            return;
        }
        File tmp = new File(getFilesDir(), DB_FILE_NAME + ".seed.tmp");
        try (InputStream in = getAssets().open(ASSET_DB_FILE_NAME);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            out.flush();
            if (!validateDatabaseFile(tmp)) {
                throw new IOException("Bundled BasketVibe database failed validation.");
            }
            if (databaseFile.exists() && !databaseFile.delete()) {
                throw new IOException("Could not replace old database.");
            }
            if (!tmp.renameTo(databaseFile)) {
                copyFile(tmp, databaseFile);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {
            // The UI reports a missing database and still allows a manual import.
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private SQLiteDatabase getDatabase() {
        synchronized (dbLock) {
            if (database != null && database.isOpen()) {
                return database;
            }
            if (!databaseFile.exists()) {
                return null;
            }
            try {
                database = SQLiteDatabase.openDatabase(
                        databaseFile.getAbsolutePath(),
                        null,
                        SQLiteDatabase.OPEN_READONLY
                );
                return database;
            } catch (Exception e) {
                database = null;
                return null;
            }
        }
    }

    private void closeDatabase() {
        synchronized (dbLock) {
            if (database != null) {
                try {
                    database.close();
                } catch (Exception ignored) {
                }
                database = null;
            }
        }
    }

    private boolean validateDatabaseFile(File candidate) {
        SQLiteDatabase test = null;
        try {
            test = SQLiteDatabase.openDatabase(candidate.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            String[] required = {"mobile_players", "mobile_comparables", "mobile_model_meta", "transfers"};
            for (String table : required) {
                try (Cursor c = test.rawQuery(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                        new String[]{table})) {
                    if (!c.moveToFirst()) {
                        return false;
                    }
                }
            }
            try (Cursor c = test.rawQuery(
                    "SELECT value FROM mobile_model_meta WHERE key='mobile_schema_version' LIMIT 1",
                    null)) {
                if (!c.moveToFirst()) {
                    return false;
                }
                String version = c.getString(0);
                if (version == null || version.trim().isEmpty()) {
                    return false;
                }
            }
            try (Cursor c = test.rawQuery("PRAGMA quick_check(1)", null)) {
                return c.moveToFirst() && "ok".equalsIgnoreCase(c.getString(0));
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (test != null) {
                test.close();
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            out.flush();
        }
    }

    private void requestDatabaseImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.sqlite3",
                "application/x-sqlite3",
                "application/octet-stream",
                "*/*"
        });
        startActivityForResult(intent, REQUEST_OPEN_DATABASE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_DATABASE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        String displayName = getDisplayName(uri);
        new Thread(() -> importDatabase(uri, displayName)).start();
    }

    private String getDisplayName(Uri uri) {
        String name = "selected file";
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.getString(idx) != null) {
                    name = c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return name;
    }

    private void importDatabase(Uri uri, String displayName) {
        File tmp = new File(getFilesDir(), DB_FILE_NAME + ".import.tmp");
        File backup = new File(getFilesDir(), DB_FILE_NAME + ".backup");
        try {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                if (in == null) {
                    throw new IOException("Could not open selected file.");
                }
                byte[] buffer = new byte[1024 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                out.flush();
            }

            if (!validateDatabaseFile(tmp)) {
                throw new IOException("That file is not a valid BasketVibe mobile database.");
            }

            closeDatabase();
            //noinspection ResultOfMethodCallIgnored
            backup.delete();
            if (databaseFile.exists() && !databaseFile.renameTo(backup)) {
                throw new IOException("Could not back up the current phone database.");
            }

            boolean replaced = tmp.renameTo(databaseFile);
            if (!replaced) {
                copyFile(tmp, databaseFile);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }

            if (!validateDatabaseFile(databaseFile)) {
                //noinspection ResultOfMethodCallIgnored
                databaseFile.delete();
                if (backup.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    backup.renameTo(databaseFile);
                }
                throw new IOException("Imported database failed its final validation check.");
            }

            //noinspection ResultOfMethodCallIgnored
            backup.delete();
            notifyImportResult(true, "Imported " + displayName, null);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            if (!databaseFile.exists() && backup.exists()) {
                //noinspection ResultOfMethodCallIgnored
                backup.renameTo(databaseFile);
            }
            notifyImportResult(false, "Import failed", e.getMessage());
        }
    }

    private void notifyImportResult(boolean success, String message, String detail) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("success", success);
            payload.put("message", message == null ? "" : message);
            payload.put("detail", detail == null ? JSONObject.NULL : detail);
        } catch (JSONException ignored) {
        }
        final String js = "window.onDatabaseImported && window.onDatabaseImported(" + payload.toString() + ");";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private JSONObject rowToJson(Cursor c) throws JSONException {
        JSONObject out = new JSONObject();
        for (int i = 0; i < c.getColumnCount(); i++) {
            String name = c.getColumnName(i);
            switch (c.getType(i)) {
                case Cursor.FIELD_TYPE_NULL:
                    out.put(name, JSONObject.NULL);
                    break;
                case Cursor.FIELD_TYPE_INTEGER:
                    out.put(name, c.getLong(i));
                    break;
                case Cursor.FIELD_TYPE_FLOAT:
                    out.put(name, c.getDouble(i));
                    break;
                case Cursor.FIELD_TYPE_STRING:
                    out.put(name, c.getString(i));
                    break;
                case Cursor.FIELD_TYPE_BLOB:
                default:
                    out.put(name, JSONObject.NULL);
                    break;
            }
        }
        return out;
    }

    private JSONArray queryRows(SQLiteDatabase db, String sql, String[] args) throws JSONException {
        JSONArray rows = new JSONArray();
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext()) {
                rows.put(rowToJson(c));
            }
        }
        return rows;
    }

    private JSONObject getStatusObject() {
        JSONObject out = new JSONObject();
        SQLiteDatabase db = getDatabase();
        try {
            out.put("ready", db != null);
            out.put("file", databaseFile.getName());
            out.put("bytes", databaseFile.exists() ? databaseFile.length() : 0L);
            if (db == null) {
                return out;
            }

            JSONObject meta = new JSONObject();
            try (Cursor c = db.rawQuery("SELECT key, value FROM mobile_model_meta", null)) {
                while (c.moveToNext()) {
                    meta.put(c.getString(0), c.getString(1));
                }
            }
            out.put("meta", meta);
            out.put("players", scalarLong(db, "SELECT COUNT(*) FROM mobile_players"));
            out.put("transfers", scalarLong(db, "SELECT COUNT(*) FROM transfers"));
            out.put("snapshots", scalarLong(db, "SELECT COUNT(*) FROM listing_snapshots"));
            out.put("listings", scalarLong(db, "SELECT COUNT(*) FROM listings"));
        } catch (Exception e) {
            try {
                out.put("error", e.getMessage());
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    private long scalarLong(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    private class BasketVibeBridge {
        private final Context context;

        BasketVibeBridge(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public String getStatusJson() {
            return getStatusObject().toString();
        }

        @JavascriptInterface
        public String searchPlayers(String query) {
            SQLiteDatabase db = getDatabase();
            if (db == null) {
                return "[]";
            }
            String q = query == null ? "" : query.trim();
            if (q.isEmpty()) {
                return "[]";
            }
            try {
                JSONArray rows = queryRows(db,
                        "SELECT player_id, player_name, position, age, height_cm, ps, is_active, " +
                                "COALESCE(current_price, latest_observed_price) AS display_price, v2_value " +
                                "FROM mobile_players " +
                                "WHERE player_name LIKE ? COLLATE NOCASE " +
                                "ORDER BY CASE WHEN lower(player_name) LIKE lower(?) THEN 0 ELSE 1 END, player_name " +
                                "LIMIT 40",
                        new String[]{"%" + q + "%", q + "%"});
                return rows.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String getPlayer(String playerId) {
            SQLiteDatabase db = getDatabase();
            if (db == null || playerId == null) {
                return "{}";
            }
            try (Cursor c = db.rawQuery("SELECT * FROM mobile_players WHERE player_id=? LIMIT 1", new String[]{playerId})) {
                if (!c.moveToFirst()) {
                    return "{}";
                }
                return rowToJson(c).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String getComparables(String playerId) {
            SQLiteDatabase db = getDatabase();
            if (db == null || playerId == null) {
                return "[]";
            }
            try {
                return queryRows(db,
                        "SELECT * FROM mobile_comparables WHERE player_id=? ORDER BY comp_rank",
                        new String[]{playerId}).toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String getTransfers(String playerId) {
            SQLiteDatabase db = getDatabase();
            if (db == null || playerId == null) {
                return "[]";
            }
            try {
                return queryRows(db,
                        "SELECT transfer_id, transfer_date, clearing_price, seller_name, buyer_name, confidence " +
                                "FROM transfers WHERE player_id=? " +
                                "ORDER BY transfer_id DESC LIMIT 25",
                        new String[]{playerId}).toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String getListings(String playerId) {
            SQLiteDatabase db = getDatabase();
            if (db == null || playerId == null) {
                return "[]";
            }
            try {
                return queryRows(db,
                        "SELECT l.listing_id, l.first_seen_at, l.last_seen_at, l.latest_end_at, l.status, l.outcome, " +
                                "(SELECT ls.live_price FROM listing_snapshots ls WHERE ls.listing_id=l.listing_id " +
                                " ORDER BY ls.snapshot_id DESC LIMIT 1) AS last_price " +
                                "FROM listings l WHERE l.player_id=? " +
                                "ORDER BY l.last_seen_at DESC LIMIT 25",
                        new String[]{playerId}).toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void requestImport() {
            runOnUiThread(MainActivity.this::requestDatabaseImport);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript(
                    "window.basketVibeBack ? String(window.basketVibeBack()) : 'false'",
                    value -> {
                        if (!"true".equals(value != null ? value.replace("\"", "") : "")) {
                            MainActivity.super.onBackPressed();
                        }
                    }
            );
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        closeDatabase();
        if (webView != null) {
            webView.removeJavascriptInterface("BasketVibe");
            webView.destroy();
        }
        super.onDestroy();
    }
}
