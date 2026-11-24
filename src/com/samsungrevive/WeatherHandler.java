package com.samsungrevive;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Stands in for samsungmobile.accu-weather.com/widget/samsungmobile/{weather-data,briefing_weather,
 * city-find}.asp, the endpoints the TouchWiz AccuWeather widget and daemon call. Emits the
 * AccuWeather widget XML (adc_database) from Open-Meteo data; adapted from HTC Revive.
 *
 * Location codes are "OM_<lat>_<lon>": Samsung builds the request URL with String.format and an
 * Apache HttpGet, so codes must stay URI-safe (no '|' or spaces).
 */
final class WeatherHandler implements MiniHttp.Handler {
    private static final String FORECAST = "http://api.open-meteo.com/v1/forecast";
    private static final String GEOCODE = "http://geocoding-api.open-meteo.com/v1/search";
    private static final String REVERSE = "http://api.bigdatacloud.net/data/reverse-geocode-client";
    private static final String DETAILS_URL = "http://open-meteo.com/";
    static final String PREFIX = "OM_";

    private final Context ctx;
    /** Android 2.2: the Galaxy S's Daily Briefing parsers. */
    static final boolean LEGACY = android.os.Build.VERSION.SDK_INT < 9;

    WeatherHandler(Context ctx) {
        this.ctx = ctx;
    }

    public MiniHttp.Response handle(MiniHttp.Request req) throws Exception {
        String p = req.path.toLowerCase(Locale.US);
        if (p.endsWith("/weather-data.asp")) return weather(req, false);
        // Daily Briefing on the Galaxy S (Android 2.2) asks briefing_weather.asp for one full city;
        // the S4's map uses the same path for its multi-city list.
        if (p.endsWith("/briefing_weather.asp")) return LEGACY ? weather(req, true) : briefing(req);
        if (p.endsWith("/detailed_weather.asp")) return detailed(req);
        if (p.endsWith("/city-find.asp")) {
            return req.query.containsKey("latitude") ? cityAt(req) : cityFind(req);
        }
        Log.w(MiniHttp.TAG, "unhandled path " + req.path);
        return MiniHttp.Response.notFound();
    }

    // ---- weather ---------------------------------------------------------------------------

    private MiniHttp.Response weather(MiniHttp.Request req, boolean briefingDoc) throws Exception {
        String code = req.q("location");
        if (code.startsWith("cityId:")) code = code.substring(7);
        if (code.length() == 0 && req.query.containsKey("latitude")) code = gpsCode(req); // Daily Briefing GPS
        Place place = resolve(code, true);
        if (place == null) {
            Log.w(MiniHttp.TAG, "cannot resolve location " + code);
            RequestLog.add("unknown city " + code);
            return MiniHttp.Response.notFound();
        }
        String lat = place.lat, lon = place.lon;
        boolean metric = "1".equals(req.q("metric"));
        String url = FORECAST + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,"
                + "wind_speed_10m,wind_direction_10m,wind_gusts_10m,pressure_msl,precipitation,visibility"
                + "&hourly=temperature_2m,apparent_temperature,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,"
                + "apparent_temperature_min,sunrise,sunset,precipitation_sum,rain_sum,snowfall_sum,"
                + "precipitation_probability_max,uv_index_max"
                + (metric ? "&temperature_unit=celsius&wind_speed_unit=kmh&precipitation_unit=mm"
                          : "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch")
                + "&timezone=auto&forecast_days=7";
        JSONObject om = new JSONObject(fetchCached(url));
        String xml = toAccuXml(om, lat, lon, place.name, metric, code);
        if (briefingDoc) xml = briefingDoc(xml);
        return MiniHttp.Response.text(200, "text/xml; charset=utf-8", xml);
    }

    /**
     * Galaxy S Daily Briefing (AccuWeatherDataXmlParse) reads a multi-city document: the city object is
     * only created on <location>, current weather is <current> and day text is <description>.
     * Its GPS parser (weather-data.asp) reads the normal document, so this is for briefing_weather only.
     */
    private static String briefingDoc(String xml) {
        int u = xml.indexOf("</units>"), e = xml.lastIndexOf("</adc_database>");
        if (u < 0 || e < 0) return xml;
        u += "</units>".length();
        String body = xml.substring(u, e)
                .replace("<currentconditions", "<current").replace("</currentconditions>", "</current>")
                .replaceAll("<txtshort>([^<]*)</txtshort>", "<txtshort>$1</txtshort><description>$1</description>");
        return xml.substring(0, u) + "<location>" + body + "</location>" + xml.substring(e);
    }

    /** OM_ code for GPS coordinates, naming it by reverse geocoding the first time. */
    private String gpsCode(MiniHttp.Request req) {
        double lat, lon;
        try {
            lat = Double.parseDouble(req.q("latitude"));
            lon = Double.parseDouble(req.q("longitude"));
        } catch (NumberFormatException e) {
            return "";
        }
        String code = String.format(Locale.US, PREFIX + "%.4f_%.4f", lat, lon);
        String name = Prefs.cityName(ctx, code);
        if (name == null || name.length() == 0) {
            try {
                JSONObject r = new JSONObject(Net.getString(REVERSE + "?latitude=" + lat + "&longitude=" + lon
                        + "&localityLanguage=en"));
                name = r.optString("city");
                if (name.length() == 0) name = r.optString("locality");
            } catch (Exception e) {
                Log.w(MiniHttp.TAG, "reverse geocode failed", e);
            }
            if (name == null || name.length() == 0) name = String.format(Locale.US, "%.2f, %.2f", lat, lon);
            Prefs.setCityName(ctx, code, clean(name));
        }
        return code;
    }

    /**
     * detailed_weather.asp (Daily Briefing): AccuWeatherHoursDataXmlparse reads hourly/hour[num] with
     * obsDate, weathericon, temperature, realfeel, dewpoint, humidity, winddirection, windspeed;
     * AccuWeatherIndexDataXmlparse reads currentconditions with airquality, uvindex@index and the usual fields.
     */
    private MiniHttp.Response detailed(MiniHttp.Request req) throws Exception {
        String code = req.q("location");
        if (code.startsWith("cityId:")) code = code.substring(7);
        if (code.length() == 0 && req.query.containsKey("latitude")) code = gpsCode(req);
        Place place = resolve(code, true);
        if (place == null) return MiniHttp.Response.notFound();
        boolean metric = !"0".equals(req.q("metric"));
        String url = FORECAST + "?latitude=" + place.lat + "&longitude=" + place.lon
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,"
                + "wind_speed_10m,wind_direction_10m,wind_gusts_10m,pressure_msl,precipitation,visibility,"
                + "dew_point_2m,cloud_cover"
                + "&hourly=temperature_2m,apparent_temperature,weather_code,dew_point_2m,relative_humidity_2m,"
                + "wind_speed_10m,wind_direction_10m,is_day"
                + "&daily=uv_index_max"
                + (metric ? "&temperature_unit=celsius&wind_speed_unit=kmh&precipitation_unit=mm"
                          : "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch")
                + "&timezone=auto&forecast_days=2";
        JSONObject om = new JSONObject(fetchCached(url));
        JSONObject cur = om.getJSONObject("current"), h = om.getJSONObject("hourly");
        String now = cur.getString("time");
        boolean isDay = cur.optInt("is_day", 1) == 1;
        int wc = cur.optInt("weather_code", 0);
        StringBuilder x = new StringBuilder(8192).append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<adc_database>");
        x.append("<hourly>");
        JSONArray times = h.getJSONArray("time");
        int start = 0;
        while (start < times.length() && times.getString(start).compareTo(now.substring(0, 13)) < 0) start++;
        for (int i = start, n = 1; i < times.length() && n <= 12; i++, n++) {
            boolean day = h.getJSONArray("is_day").optInt(i, 1) == 1;
            int c = h.getJSONArray("weather_code").optInt(i, 0);
            x.append("<hour num=\"").append(n).append("\">");
            tag(x, "obsDate", clock12(times.getString(i)));
            tag(x, "weathericon", icon(c, day));
            tag(x, "temperature", round(h.getJSONArray("temperature_2m").optDouble(i, 0)));
            tag(x, "realfeel", round(h.getJSONArray("apparent_temperature").optDouble(i, 0)));
            tag(x, "dewpoint", round(h.getJSONArray("dew_point_2m").optDouble(i, 0)));
            tag(x, "humidity", round(h.getJSONArray("relative_humidity_2m").optDouble(i, 0)) + "%");
            tag(x, "winddirection", compass(h.getJSONArray("wind_direction_10m").optDouble(i, 0)));
            tag(x, "windspeed", round(h.getJSONArray("wind_speed_10m").optDouble(i, 0)));
            x.append("</hour>");
        }
        x.append("</hourly>");
        x.append("<currentconditions daylight=\"").append(isDay ? "True" : "False").append("\">");
        tag(x, "airquality", "");
        double pres = cur.optDouble("pressure_msl", 1013);
        tag(x, "pressure", metric ? String.format(Locale.US, "%.0f", pres) : String.format(Locale.US, "%.2f", pres / 33.8639));
        tag(x, "temperature", round(cur.optDouble("temperature_2m", 0)));
        tag(x, "realfeel", round(cur.optDouble("apparent_temperature", 0)));
        tag(x, "humidity", round(cur.optDouble("relative_humidity_2m", 0)) + "%");
        tag(x, "weathertext", text(wc, isDay));
        tag(x, "weathericon", icon(wc, isDay));
        tag(x, "windgusts", round(cur.optDouble("wind_gusts_10m", 0)));
        tag(x, "windspeed", round(cur.optDouble("wind_speed_10m", 0)));
        tag(x, "winddirection", compass(cur.optDouble("wind_direction_10m", 0)));
        double visM = cur.optDouble("visibility", 16093);
        tag(x, "visibility", round(metric ? Math.min(visM / 1000, 16) : Math.min(visM / 1609.344, 10)));
        tag(x, "precip", String.format(Locale.US, metric ? "%.1f" : "%.2f", cur.optDouble("precipitation", 0)));
        tag(x, "dewpoint", round(cur.optDouble("dew_point_2m", 0)));
        tag(x, "cloudcover", round(cur.optDouble("cloud_cover", 0)) + "%");
        double uv = om.getJSONObject("daily").getJSONArray("uv_index_max").optDouble(0, 0);
        x.append("<uvindex index=\"").append(round(uv)).append("\">").append(uvText(uv)).append("</uvindex>");
        x.append("</currentconditions></adc_database>");
        return MiniHttp.Response.text(200, "text/xml; charset=utf-8", x.toString());
    }

    private static String uvText(double uv) {
        return uv < 3 ? "Low" : uv < 6 ? "Moderate" : uv < 8 ? "High" : uv < 11 ? "Very High" : "Extreme";
    }

    /**
     * The map asks for many cities at once: location=cityId:a,cityId:b,... It is parsed by
     * ParserHandlerLocList: <location><local>city,state,cityId,lat,lon</local>
     * <current>temperature,weathericon</current></location>. Stock AccuWeather ids are skipped.
     */
    private MiniHttp.Response briefing(MiniHttp.Request req) throws Exception {
        boolean metric = "1".equals(req.q("metric"));
        java.util.List<String> codes = new java.util.ArrayList<String>();
        java.util.List<Place> places = new java.util.ArrayList<Place>();
        int fresh = 0;
        for (String part : req.q("location").split(",")) {
            String code = part.trim();
            if (code.startsWith("cityId:")) code = code.substring(7);
            if (code.length() == 0) continue;
            // Geocode at most 10 new stock cities per batch; the rest resolve on later refreshes.
            boolean allowGeocode = fresh < 10;
            Place p;
            try {
                boolean cached = code.startsWith(PREFIX) || Prefs.geo(ctx, code) != null;
                p = resolve(code, allowGeocode);
                if (p != null && !cached) fresh++;
            } catch (Exception e) {
                Log.w(MiniHttp.TAG, "briefing: cannot resolve " + code, e);
                p = null;
            }
            if (p == null) continue;
            codes.add(code);
            places.add(p);
        }
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<adc_database>");
        if (!places.isEmpty()) {
            StringBuilder la = new StringBuilder(), lo = new StringBuilder();
            for (Place p : places) {
                if (la.length() > 0) {
                    la.append(',');
                    lo.append(',');
                }
                la.append(p.lat);
                lo.append(p.lon);
            }
            // Open-Meteo takes many coordinates at once and returns an array (an object for one).
            String body = fetchCached(FORECAST + "?latitude=" + la + "&longitude=" + lo
                    + "&current=temperature_2m,is_day,weather_code&timezone=auto"
                    + (metric ? "" : "&temperature_unit=fahrenheit"));
            JSONArray arr = body.trim().startsWith("[") ? new JSONArray(body) : new JSONArray().put(new JSONObject(body));
            for (int i = 0; i < places.size() && i < arr.length(); i++) {
                Place p = places.get(i);
                JSONObject cur = arr.getJSONObject(i).getJSONObject("current");
                x.append("<location><local>");
                tag(x, "city", p.name);
                tag(x, "state", "");
                tag(x, "cityId", codes.get(i));
                tag(x, "lat", p.lat);
                tag(x, "lon", p.lon);
                x.append("</local><current>");
                tag(x, "temperature", round(cur.getDouble("temperature_2m")));
                tag(x, "weathericon", icon(cur.optInt("weather_code", 0), cur.optInt("is_day", 1) == 1));
                x.append("</current></location>");
            }
        }
        x.append("</adc_database>");
        return MiniHttp.Response.text(200, "text/xml; charset=utf-8", x.toString());
    }

    // ---- location codes --------------------------------------------------------------------

    static final class Place {
        String lat, lon, name;
    }

    /**
     * OM_<lat>_<lon> codes are our own. Numeric codes are real AccuWeather cityIds (the S4's
     * offline city search, preloaded map cities): looked up in accucities.tsv, geocoded once by
     * name/state/country and cached. Returns null if unknown or if geocoding is not allowed.
     */
    private Place resolve(String code, boolean allowGeocode) throws Exception {
        Place p = new Place();
        if (code.startsWith(PREFIX)) {
            String[] f = code.substring(PREFIX.length()).split("_");
            if (f.length < 2) return null;
            Double.parseDouble(f[0]);
            Double.parseDouble(f[1]);
            p.lat = f[0];
            p.lon = f[1];
            p.name = Prefs.cityName(ctx, code);
            return p;
        }
        String[] c = AccuCities.byId(ctx, code);
        if (c == null) return null;
        p.name = c[0];
        String cached = Prefs.geo(ctx, code);
        if (cached != null) {
            String[] ll = cached.split(",");
            p.lat = ll[0];
            p.lon = ll[1];
            return p;
        }
        if (!allowGeocode) return null;
        JSONObject hit = geocodeCity(c[0], c[1], c[2]);
        if (hit == null) return null;
        p.lat = String.format(Locale.US, "%.4f", hit.getDouble("latitude"));
        p.lon = String.format(Locale.US, "%.4f", hit.getDouble("longitude"));
        Prefs.setGeo(ctx, code, p.lat + "," + p.lon);
        return p;
    }

    /** Best Open-Meteo match for an AccuWeather city: same country first, then same state. */
    private static JSONObject geocodeCity(String city, String state, String country) throws Exception {
        String q = city.replace("`", "").trim();
        JSONObject o = new JSONObject(Net.getString(GEOCODE + "?name=" + URLEncoder.encode(q, "UTF-8")
                + "&count=10&language=en"));
        JSONArray r = o.optJSONArray("results");
        if (r == null || r.length() == 0) {
            Log.w(MiniHttp.TAG, "geocode found nothing for " + city + ", " + country);
            return null;
        }
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < r.length(); i++) {
            JSONObject h = r.getJSONObject(i);
            int score = 0;
            if (country.equalsIgnoreCase(h.optString("country"))) score += 2;
            if (state.length() > 0 && state.equalsIgnoreCase(h.optString("admin1"))) score += 1;
            if (score > bestScore) {
                best = h;
                bestScore = score;
            }
        }
        return best;
    }

    private static final long CACHE_MS = 10 * 60 * 1000L;
    private final Map<String, Object[]> cache = new HashMap<String, Object[]>();

    /** Refreshes fire one request per saved city in a burst; Open-Meteo answers bursts with 503. */
    private String fetchCached(String url) throws Exception {
        synchronized (cache) {
            Object[] hit = cache.get(url);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < CACHE_MS) return (String) hit[1];
        }
        String body;
        try {
            body = Net.getString(url);
        } catch (java.io.IOException e) {
            Thread.sleep(1500);
            body = Net.getString(url);
        }
        synchronized (cache) {
            cache.put(url, new Object[]{System.currentTimeMillis(), body});
        }
        return body;
    }

    static String toAccuXml(JSONObject om, String lat, String lon, String name, boolean metric, String locCode) throws Exception {
        JSONObject cur = om.getJSONObject("current");
        JSONObject hourly = om.getJSONObject("hourly");
        JSONObject daily = om.getJSONObject("daily");
        int offsetSec = om.optInt("utc_offset_seconds", 0);
        String nowLocal = cur.getString("time"); // yyyy-MM-ddTHH:mm, city-local
        boolean isDay = cur.optInt("is_day", 1) == 1;
        int code = cur.optInt("weather_code", 0);

        StringBuilder x = new StringBuilder(8192);
        x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<adc_database>");
        x.append("<units>");
        tag(x, "temp", metric ? "C" : "F");
        tag(x, "dist", metric ? "KM" : "MI");
        tag(x, "speed", metric ? "KM/H" : "MPH");
        tag(x, "pres", metric ? "MB" : "IN");
        tag(x, "prec", metric ? "MM" : "IN");
        x.append("</units>");

        x.append("<local>");
        tag(x, "city", name);
        tag(x, "state", "");
        tag(x, "cityId", locCode);
        tag(x, "lat", lat);
        tag(x, "lon", lon);
        tag(x, "time", nowLocal.substring(11, 16));
        String gmt = gmtOffset(offsetSec);
        tag(x, "timeZone", gmt);
        tag(x, "obsDaylight", "0");
        tag(x, "currentGmtOffset", gmt);
        tag(x, "timeZoneAbbreviation", om.optString("timezone_abbreviation"));
        x.append("</local>");

        x.append("<currentconditions daylight=\"").append(isDay ? "True" : "False").append("\">");
        tag(x, "url", DETAILS_URL + "?cityId=" + locCode);
        tag(x, "observationtime", clock12(nowLocal));
        double pres = cur.optDouble("pressure_msl", 1013);
        tag(x, "pressure", metric ? String.format(Locale.US, "%.0f", pres) : String.format(Locale.US, "%.2f", pres / 33.8639));
        tag(x, "temperature", round(cur.getDouble("temperature_2m")));
        tag(x, "realfeel", round(cur.getDouble("apparent_temperature")));
        tag(x, "humidity", round(cur.optDouble("relative_humidity_2m", 0)) + "%");
        tag(x, "weathertext", text(code, isDay));
        tag(x, "weathericon", icon(code, isDay));
        tag(x, "windgusts", round(cur.optDouble("wind_gusts_10m", 0)));
        tag(x, "windspeed", round(cur.optDouble("wind_speed_10m", 0)));
        tag(x, "winddirection", compass(cur.optDouble("wind_direction_10m", 0)));
        double visM = cur.optDouble("visibility", 16093);
        tag(x, "visibility", round(metric ? Math.min(visM / 1000, 16) : Math.min(visM / 1609.344, 10)));
        tag(x, "precip", String.format(Locale.US, metric ? "%.1f" : "%.2f", cur.optDouble("precipitation", 0)));
        x.append("<uvindex index=\"").append(round(daily.getJSONArray("uv_index_max").optDouble(0, 0))).append("\"></uvindex>");
        x.append("</currentconditions>");
        // ParserHandlerSingle reads sunrise/sunset from <planets><sun rise=".." set=".."/>.
        x.append("<planets><sun rise=\"").append(clock12(daily.getJSONArray("sunrise").getString(0)))
                .append("\" set=\"").append(clock12(daily.getJSONArray("sunset").getString(0)))
                .append("\"/></planets>");

        x.append("<forecast>");
        JSONArray days = daily.getJSONArray("time");
        JSONArray hTime = hourly.getJSONArray("time");
        for (int d = 0; d < days.length(); d++) {
            String date = days.getString(d);
            int dayCode = daily.getJSONArray("weather_code").optInt(d, 0);
            String hi = round(daily.getJSONArray("temperature_2m_max").getDouble(d));
            String lo = round(daily.getJSONArray("temperature_2m_min").getDouble(d));
            String feelHi = round(daily.getJSONArray("apparent_temperature_max").getDouble(d));
            String feelLo = round(daily.getJSONArray("apparent_temperature_min").getDouble(d));
            String precip = String.format(Locale.US, metric ? "%.1f" : "%.2f", daily.getJSONArray("precipitation_sum").optDouble(d, 0));
            String pop = round(daily.getJSONArray("precipitation_probability_max").optDouble(d, 0));
            double rainD = daily.has("rain_sum") ? daily.getJSONArray("rain_sum").optDouble(d, 0) : 0;
            double snowD = daily.has("snowfall_sum") ? daily.getJSONArray("snowfall_sum").optDouble(d, 0) : 0;
            String rain = String.format(Locale.US, metric ? "%.1f" : "%.2f", rainD);
            String snow = String.format(Locale.US, metric ? "%.1f" : "%.2f", snowD);
            String rainPop = snowD > 0 && rainD == 0 ? "0" : pop;
            String snowPop = snowD > 0 ? pop : "0";
            Span night = span(hourly, hTime, date + "T18:00", 12);
            int nightCode = night.n > 0 ? night.code : dayCode;

            x.append("<day number=\"").append(d + 1).append("\">");
            tag(x, "url", DETAILS_URL + "?cityId=" + locCode);
            tag(x, "obsdate", usDate(date));
            tag(x, "daycode", dayName(date));
            tag(x, "sunrise", clock12(daily.getJSONArray("sunrise").getString(d)));
            tag(x, "sunset", clock12(daily.getJSONArray("sunset").getString(d)));
            x.append("<daytime>");
            tag(x, "txtshort", text(dayCode, true));
            tag(x, "txtlong", text(dayCode, true));
            tag(x, "weathericon", icon(dayCode, true));
            tag(x, "hightemperature", hi);
            tag(x, "lowtemperature", lo);
            tag(x, "realfeelhigh", feelHi);
            tag(x, "realfeellow", feelLo);
            amounts(x, rain, snow, precip, rainPop, snowPop, pop);
            x.append("</daytime><nighttime>");
            tag(x, "txtshort", text(nightCode, false));
            tag(x, "txtlong", text(nightCode, false));
            tag(x, "weathericon", icon(nightCode, false));
            tag(x, "hightemperature", night.n > 0 ? round(night.hi) : hi);
            tag(x, "lowtemperature", night.n > 0 ? round(night.lo) : lo);
            tag(x, "realfeelhigh", night.n > 0 ? round(night.feelHi) : feelHi);
            tag(x, "realfeellow", night.n > 0 ? round(night.feelLo) : feelLo);
            amounts(x, rain, snow, precip, rainPop, snowPop, pop);
            x.append("</nighttime></day>");
        }
        x.append("</forecast></adc_database>");
        return x.toString();
    }

    /**
     * The 2013 TouchWiz parser (Galaxy S4) parses these per daytime/nighttime and throws on an
     * empty value: amounts as floats, probabilities as ints. Open-Meteo has no ice; it stays 0.
     * Never put <realfeel> inside daytime/nighttime: its startElement also sets the parser's
     * daytime flag, which routes the night amounts into the day buffers ("0.00.0").
     */
    private static void amounts(StringBuilder x, String rain, String snow, String precip,
                                String rainPop, String snowPop, String pop) {
        tag(x, "rainamount", rain);
        tag(x, "snowamount", snow);
        tag(x, "iceamount", "0");
        tag(x, "precipamount", precip);
        tag(x, "rainProbability", rainPop);
        tag(x, "snowProbability", snowPop);
        tag(x, "iceProbability", "0");
        tag(x, "precipitationProbability", pop);
        tag(x, "tstormprob", "0");
    }

    private static final class Span {
        int n, code;
        double hi = -999, lo = 999, feelHi = -999, feelLo = 999;
    }

    /** Aggregates `hours` hourly samples starting at `from` (local ISO time). */
    private static Span span(JSONObject hourly, JSONArray hTime, String from, int hours) throws Exception {
        Span s = new Span();
        int i0 = -1;
        for (int i = 0; i < hTime.length(); i++) {
            if (hTime.getString(i).equals(from)) {
                i0 = i;
                break;
            }
        }
        if (i0 < 0) return s;
        int worst = -1;
        for (int i = i0; i < Math.min(i0 + hours, hTime.length()); i++) {
            double t = hourly.getJSONArray("temperature_2m").getDouble(i);
            double f = hourly.getJSONArray("apparent_temperature").getDouble(i);
            s.hi = Math.max(s.hi, t);
            s.lo = Math.min(s.lo, t);
            s.feelHi = Math.max(s.feelHi, f);
            s.feelLo = Math.min(s.feelLo, f);
            worst = Math.max(worst, hourly.getJSONArray("weather_code").optInt(i, 0));
            s.n++;
        }
        s.code = Math.max(worst, 0);
        return s;
    }

    // ---- city search -----------------------------------------------------------------------

    private MiniHttp.Response cityFind(MiniHttp.Request req) throws Exception {
        // English names: MenuAdd only accepts [0-9a-zA-Z', .-], so "Österreich" would be dropped.
        JSONObject o = new JSONObject(Net.getString(GEOCODE + "?name=" + URLEncoder.encode(req.q("location"), "UTF-8")
                + "&count=10&language=en"));
        JSONArray results = o.optJSONArray("results");
        if (results == null) results = new JSONArray();
        StringBuilder x = cityListStart(results.length());
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String admin = r.optString("admin1");
            String country = r.optString("country");
            city(x, r.optString("name"), admin.length() > 0 ? admin : country, country,
                    r.getDouble("latitude"), r.getDouble("longitude"));
        }
        return cityListEnd(x);
    }

    /** "Current location": city-find.asp?returnGeoPosition=1&latitude=..&longitude=.. */
    private MiniHttp.Response cityAt(MiniHttp.Request req) throws Exception {
        double lat, lon;
        try {
            lat = Double.parseDouble(req.q("latitude"));
            lon = Double.parseDouble(req.q("longitude"));
        } catch (NumberFormatException e) {
            // The daemon has AES helpers for coordinates; if they ever arrive encrypted, this shows them.
            Log.w(MiniHttp.TAG, "unreadable coordinates: " + req.q("latitude") + " / " + req.q("longitude"));
            RequestLog.add("GPS coords unreadable: " + req.q("latitude"));
            return MiniHttp.Response.notFound();
        }
        String name = "", state = "", country = "";
        try {
            JSONObject r = new JSONObject(Net.getString(REVERSE + "?latitude=" + lat + "&longitude=" + lon
                    + "&localityLanguage=en"));
            name = r.optString("city");
            if (name.length() == 0) name = r.optString("locality");
            state = r.optString("principalSubdivision");
            country = r.optString("countryName");
        } catch (Exception e) {
            Log.w(MiniHttp.TAG, "reverse geocode failed", e);
        }
        if (name.length() == 0) name = String.format(Locale.US, "%.2f, %.2f", lat, lon);
        // GPS answers go through parseDetailWeatherLocation, i.e. the same ParserHandlerList as search.
        StringBuilder x = cityListStart(1);
        city(x, name, state.length() > 0 ? state : country, country, lat, lon);
        return cityListEnd(x);
    }

    private static StringBuilder cityListStart(int n) {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        x.append("<adc_database><citylist us=\"0\" intl=\"").append(n).append("\" extra_cities=\"0\">");
        return x;
    }

    private static MiniHttp.Response cityListEnd(StringBuilder x) {
        x.append("</citylist></adc_database>");
        return MiniHttp.Response.text(200, "text/xml; charset=utf-8", x.toString());
    }

    /**
     * One <location> as ParserHandlerList wants it: intl (on citylist), cnt, city, state, location,
     * latitude and longitude must all be non-empty or the entry is dropped. The list label is built
     * from state "Country (Region)" -> "Region, Country"; without brackets it appends ", USA".
     */
    private void city(StringBuilder x, String name, String region, String country, double lat, double lon) {
        String code = String.format(Locale.US, "%s%.4f_%.4f", PREFIX, lat, lon);
        name = clean(name);
        country = clean(country);
        region = clean(region);
        if (country.length() == 0) country = region.length() > 0 ? region : name;
        if (region.length() == 0) region = country;
        Prefs.setCityName(ctx, code, name);
        String la = String.format(Locale.US, "%.4f", lat), lo = String.format(Locale.US, "%.4f", lon);
        // "us" is read by the 2011 ParserHandlerLocation (Galaxy S2); "1" would append ", USA".
        x.append("<location us=\"0\" cnt=\"1\" city=\"").append(esc(name))
                .append("\" state=\"").append(esc(country + " (" + region + ")"))
                .append("\" location=\"cityId:").append(esc(code))
                .append("\" latitude=\"").append(la).append("\" longitude=\"").append(lo)
                .append("\"/>");
    }

    /** Strips what the label parsing splits on, and anything MenuAdd's ASCII check would reject. */
    private static String clean(String s) {
        if (s == null) return "";
        if (android.os.Build.VERSION.SDK_INT >= 9) { // java.text.Normalizer is API 9
            s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        }
        s = s.replaceAll("[^0-9a-zA-Z' .-]", "");
        return s.replaceAll(" +", " ").trim();
    }

    // ---- mapping helpers -------------------------------------------------------------------

    /** WMO weather code to AccuWeather icon number (1-44), which the TouchWiz icon set is keyed on. */
    static String icon(int wmo, boolean day) {
        int a;
        switch (wmo) {
            case 0: a = day ? 1 : 33; break;
            case 1: a = day ? 2 : 34; break;
            case 2: a = day ? 3 : 35; break;
            case 3: a = 7; break;
            case 45: case 48: a = 11; break;
            case 51: case 53: case 55: a = day ? 14 : 39; break;
            case 56: case 57: case 66: case 67: a = 26; break;
            case 61: a = day ? 14 : 39; break;
            case 63: a = 12; break;
            case 65: a = 18; break;
            case 71: case 77: a = day ? 19 : 43; break;
            case 73: case 75: a = 22; break;
            case 80: a = day ? 14 : 39; break;
            case 81: a = day ? 13 : 40; break;
            case 82: a = 18; break;
            case 85: a = day ? 21 : 43; break;
            case 86: a = day ? 23 : 44; break;
            case 95: a = day ? 16 : 42; break;
            case 96: case 99: a = 15; break;
            default: a = day ? 6 : 38; break;
        }
        return String.format(Locale.US, "%02d", a);
    }

    static String text(int wmo, boolean day) {
        switch (wmo) {
            case 0: return day ? "Sunny" : "Clear";
            case 1: return day ? "Mostly sunny" : "Mostly clear";
            case 2: return day ? "Partly sunny" : "Partly cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: return "Light snow";
            case 73: return "Snow";
            case 75: return "Heavy snow";
            case 77: return "Snow grains";
            case 80: case 81: return "Showers";
            case 82: return "Heavy showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorms";
            case 96: case 99: return "Thunderstorms with hail";
            default: return "Cloudy";
        }
    }

    private static String compass(double deg) {
        String[] p = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return p[(int) Math.round(((deg % 360) + 360) % 360 / 22.5) % 16];
    }

    private static String gmtOffset(int sec) {
        if (sec % 3600 == 0) return String.valueOf(sec / 3600);
        return String.format(Locale.US, "%.1f", sec / 3600f);
    }

    private static String usDate(String iso) throws Exception {
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        in.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat out = new SimpleDateFormat("M/d/yyyy", Locale.US);
        out.setTimeZone(TimeZone.getTimeZone("UTC"));
        return out.format(in.parse(iso));
    }

    private static String dayName(String iso) throws Exception {
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        in.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat out = new SimpleDateFormat("EEEE", Locale.US);
        out.setTimeZone(TimeZone.getTimeZone("UTC"));
        return out.format(in.parse(iso));
    }

    /** "2026-11-23T07:15" -> "7:15 AM", as AccuWeather sent it. */
    private static String clock12(String iso) {
        int h = Integer.parseInt(iso.substring(11, 13));
        String m = iso.substring(14, 16);
        return (h % 12 == 0 ? 12 : h % 12) + ":" + m + (h < 12 ? " AM" : " PM");
    }

    private static String round(double v) {
        return String.valueOf(Math.round(v));
    }

    private static void tag(StringBuilder x, String name, String value) {
        x.append('<').append(name).append('>').append(esc(value)).append("</").append(name).append('>');
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
