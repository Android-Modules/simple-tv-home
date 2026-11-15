package dev.dalong.tvhome;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Weather {
    private static final long OPEN_METEO_INTERVAL = 30 * 60_000L;
    private static final long MET_NORWAY_INTERVAL = 3 * 60 * 60_000L;
    private static final Uri TV_WEATHER = Uri.parse("content://com.xiaomi.tweather.provider/syscity");

    private Weather() {
    }

    static final class City {
        final String name;
        final String region;
        final double latitude;
        final double longitude;

        City(String name, String region, double latitude, double longitude) {
            this.name = name;
            this.region = region;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        String displayName() {
            return region.isEmpty() ? name : name + " · " + region;
        }

        boolean matches(City other) {
            return other != null && name.equals(other.name)
                    && Math.abs(latitude - other.latitude) < 0.01
                    && Math.abs(longitude - other.longitude) < 0.01;
        }
    }

    static City tvCity(ContentResolver resolver) {
        try {
            String value = Settings.System.getString(resolver, "mitv_location");
            if (value != null && !value.isEmpty()) {
                return parseCity(new JSONObject(value), "province");
            }
        } catch (JSONException | SecurityException ignored) {
            // Some firmware versions keep this value private.
        }
        return null;
    }

    static City networkCity() throws IOException, JSONException {
        JSONObject location = request("https://ipwho.is/?fields=success,city,region,latitude,longitude&lang=zh");
        if (!location.optBoolean("success")) {
            throw new IOException("IP city unavailable");
        }
        City city = parseCity(location, "region");
        if (city == null) {
            throw new IOException("IP city missing");
        }
        return city;
    }

    private static City parseCity(JSONObject location, String regionKey) {
        String name = location.optString("city", "").trim();
        double latitude = location.optDouble("latitude");
        double longitude = location.optDouble("longitude");
        if (name.isEmpty() || "null".equals(name) || !Double.isFinite(latitude)
                || !Double.isFinite(longitude) || Math.abs(latitude) > 90
                || Math.abs(longitude) > 180) {
            return null;
        }
        return new City(name, location.optString(regionKey, ""), latitude, longitude);
    }

    static Reading tvWeather(ContentResolver resolver, City target) {
        City systemCity = tvCity(resolver);
        if (!target.matches(systemCity)) {
            return null;
        }
        try {
            String override = Settings.System.getString(resolver, "mitv_user_location");
            if (override != null && !override.isEmpty()
                    && !target.matches(parseCity(new JSONObject(override), "province"))) {
                return null;
            }
        } catch (JSONException | SecurityException ignored) {
            return null;
        }
        try (Cursor row = resolver.query(TV_WEATHER, null, null, null, null)) {
            if (row != null && row.getColumnCount() > 1 && row.moveToFirst() && !row.isNull(1)) {
                String value = row.getString(1).replace("℃", "").replace("°C", "").trim();
                double degrees = Double.parseDouble(value);
                if (Double.isFinite(degrees) && degrees >= -100 && degrees <= 70) {
                    return new Reading(Math.round(degrees) + "°C", 15 * 60_000L);
                }
            }
        } catch (IllegalArgumentException | SecurityException ignored) {
            // The firmware provider is optional and may return a placeholder.
        }
        return null;
    }

    static final class Reading {
        final String text;
        final long refreshAfter;

        Reading(String text, long refreshAfter) {
            this.text = text;
            this.refreshAfter = refreshAfter;
        }
    }

    static List<City> search(String name) throws IOException, JSONException {
        String query = URLEncoder.encode(name, "UTF-8");
        JSONArray matches = request("https://geocoding-api.open-meteo.com/v1/search?name="
                + query + "&count=5&language=zh").optJSONArray("results");
        if (matches == null && name.endsWith("市") && name.length() > 2) {
            return search(name.substring(0, name.length() - 1));
        }
        List<City> cities = new ArrayList<>();
        if (matches != null) {
            for (int index = 0; index < matches.length(); index++) {
                JSONObject result = matches.getJSONObject(index);
                String city = result.getString("name");
                String region = result.optString("admin1", "");
                if (city.equals(region) || region.isEmpty()) {
                    region = result.optString("country", "");
                } else {
                    String country = result.optString("country", "");
                    if (!country.isEmpty()) {
                        region += "，" + country;
                    }
                }
                cities.add(new City(city, region, result.getDouble("latitude"),
                        result.getDouble("longitude")));
            }
        }
        return cities;
    }

    static Reading current(City city) throws IOException, JSONException {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + city.latitude
                + "&longitude=" + city.longitude + "&current=temperature_2m,weather_code";
        try {
            JSONObject now = request(url).getJSONObject("current");
            return new Reading(temperature(condition(now.getInt("weather_code")),
                    now.getDouble("temperature_2m")), OPEN_METEO_INTERVAL);
        } catch (IOException | JSONException unavailable) {
            return metNorway(city);
        }
    }

    private static Reading metNorway(City city) throws IOException, JSONException {
        String url = String.format(Locale.ROOT,
                "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f",
                city.latitude, city.longitude);
        HttpURLConnection connection = connect(url);
        connection.setRequestProperty("User-Agent", "SimpleTvHome/1.3 (dev.dalong.tvhome)");
        try {
            JSONObject data = readJson(connection).getJSONObject("properties")
                    .getJSONArray("timeseries").getJSONObject(0).getJSONObject("data");
            double degrees = data.getJSONObject("instant").getJSONObject("details")
                    .getDouble("air_temperature");
            JSONObject next = data.optJSONObject("next_1_hours");
            if (next == null) {
                next = data.optJSONObject("next_6_hours");
            }
            String symbol = next == null ? "" : next.getJSONObject("summary").optString("symbol_code");
            long untilExpiry = connection.getExpiration() - System.currentTimeMillis();
            return new Reading(temperature(metCondition(symbol), degrees),
                    Math.max(MET_NORWAY_INTERVAL, untilExpiry));
        } finally {
            connection.disconnect();
        }
    }

    private static String temperature(String condition, double degrees) {
        return condition + " " + Math.round(degrees) + "°C";
    }

    private static String metCondition(String symbol) {
        if (symbol.contains("thunder")) return "雷雨";
        if (symbol.contains("sleet")) return "雨夹雪";
        if (symbol.contains("snow")) return "雪";
        if (symbol.contains("rain")) return "雨";
        if (symbol.startsWith("fog")) return "雾";
        if (symbol.startsWith("cloudy")) return "阴";
        if (symbol.startsWith("partlycloudy")) return "多云";
        if (symbol.startsWith("clearsky") || symbol.startsWith("fair")) return "晴";
        return "天气";
    }

    private static JSONObject request(String url) throws IOException, JSONException {
        HttpURLConnection connection = connect(url);
        try {
            return readJson(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(4500);
        connection.setReadTimeout(4500);
        return connection;
    }

    private static JSONObject readJson(HttpURLConnection connection) throws IOException, JSONException {
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            throw new IOException("Weather service HTTP " + status);
        }
        try (BufferedReader input = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = input.readLine()) != null) {
                json.append(line);
            }
            return new JSONObject(json.toString());
        }
    }

    private static String condition(int code) {
        switch (code) {
            case 0:
            case 1:
                return "晴";
            case 2:
                return "多云";
            case 3:
                return "阴";
            case 45:
            case 48:
                return "雾";
            case 56:
            case 57:
            case 66:
            case 67:
                return "冻雨";
            case 51:
            case 53:
            case 55:
                return "毛毛雨";
            case 61:
            case 63:
            case 65:
            case 80:
            case 81:
            case 82:
                return "雨";
            case 71:
            case 73:
            case 75:
            case 77:
            case 85:
            case 86:
                return "雪";
            case 95:
            case 96:
            case 99:
                return "雷雨";
            default:
                return "天气";
        }
    }
}
