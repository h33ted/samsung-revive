package com.samsungrevive;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stands in for the TouchWiz Yahoo! News and Yahoo! Finance (stock clock) widgets' backends.
 *
 * <p>News: the daemon issues YQL GETs to samsung-mobile.query.yahooapis.com/v1/yql:
 * {@code select * from partner.news.categories where region='us'} (YahooNewsHeadXmlParse: meta/news/
 * yahoo_mobile_url, results/entry/title,category,region,lang; an entry is stored when lang closes) and
 * {@code select * from partner.news.feeds where category='..' and region='..' and lang='..'}
 * (YahooNewsXmlParse: results/entry with title, link@href, id, published, summary, content@url, date,
 * credit; an entry is stored when credit closes, de-duplicated by id). Categories come from the same
 * feed list as AP News.
 *
 * <p>Finance: YQL {@code partner.finance.quotes where symbol in ('A','B')} -> results/quote with
 * name, exchange, price, change (+/-), chg_percent (no %), day_high ... details_url, ts;
 * {@code partner.finance.symbols where query='x'} -> result/name,symbol,exchange,exchange_name,type
 * (ETF|Stock); {@code partner.finance.charts where symbol='x' and range='1d'} -> value/close,high,time
 * (epoch seconds),volume,date. The same element names answer the older XML POSTs to
 * samsung-finance.api.mobile.yahoo.com (getquotes / getsymbol / getchart). The parsers match local
 * element names, so the wrapping is free. Data from Yahoo Finance's public chart/search JSON.
 */
final class YahooHandler implements MiniHttp.Handler {
    static final String YQL_HOST = "samsung-mobile.query.yahooapis.com";
    static final String FIN_HOST = "samsung-finance.api.mobile.yahoo.com";

    private static final String CHART = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final String SEARCH = "https://query1.finance.yahoo.com/v1/finance/search";
    private static final long QUOTE_CACHE_MS = 60 * 1000L;
    private static final long CHART_CACHE_MS = 5 * 60 * 1000L;
    private static final Map<String, String[]> RANGES = new HashMap<String, String[]>();

    static {
        RANGES.put("1d", new String[]{"1d", "5m"});
        RANGES.put("5d", new String[]{"5d", "15m"});
        RANGES.put("1m", new String[]{"1mo", "1d"});
        RANGES.put("3m", new String[]{"3mo", "1d"});
        RANGES.put("6m", new String[]{"6mo", "1d"});
        RANGES.put("1y", new String[]{"1y", "1d"});
        RANGES.put("2y", new String[]{"2y", "1wk"});
        RANGES.put("5y", new String[]{"5y", "1wk"});
    }

    private final Context ctx;
    private final NewsHandler news;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private final Map<String, Object[]> cache = new HashMap<String, Object[]>();

    YahooHandler(Context ctx, NewsHandler news) {
        this.ctx = ctx;
        this.news = news;
    }

    static boolean handles(String host) {
        return YQL_HOST.equals(host) || FIN_HOST.equals(host);
    }

    public MiniHttp.Response handle(MiniHttp.Request req) throws Exception {
        String xml;
        if (req.body.length > 0 && req.method.equalsIgnoreCase("POST")) {
            xml = post(new String(req.body, "UTF-8"));
        } else {
            String q = req.q("q");
            String ql = q.toLowerCase(Locale.US);
            if (ql.contains("partner.news.categories")) xml = categories(q);
            else if (ql.contains("partner.news.feeds")) xml = feed(q);
            else if (ql.contains("partner.finance.quotes")) xml = yql(quotes(quoted(q.substring(Math.max(0, ql.indexOf(" where "))))));
            else if (ql.contains("partner.finance.symbols")) xml = yql(search(clause(q, "query")));
            else if (ql.contains("partner.finance.charts")) xml = yql(chart(clause(q, "symbol"), clause(q, "range")));
            else {
                Log.w(MiniHttp.TAG, "yahoo: unhandled " + req.target);
                return MiniHttp.Response.notFound();
            }
        }
        return MiniHttp.Response.text(200, "text/xml; charset=utf-8", xml);
    }

    // ---- News ----

    private String categories(String q) {
        String region = or(clause(q, "region"), "us"), lang = or(clause(q, "lang"), "en-US");
        StringBuilder x = new StringBuilder("<meta><news>");
        tag(x, "yahoo_mobile_url", "http://news.yahoo.com/");
        x.append("</news></meta><results>");
        List<String[]> feeds = Prefs.newsFeeds(ctx);
        for (int i = 0; i < feeds.size(); i++) {
            x.append("<entry>");
            tag(x, "title", feeds.get(i)[0]);
            tag(x, "category", String.valueOf(i + 1));
            tag(x, "region", region);
            tag(x, "lang", lang);
            x.append("</entry>");
        }
        return yqlRaw(x.append("</results>").toString(), feeds.size());
    }

    private String feed(String q) throws Exception {
        List<String[]> feeds = Prefs.newsFeeds(ctx);
        String cat = clause(q, "category");
        int c = 1;
        try {
            c = Integer.parseInt(cat);
        } catch (NumberFormatException e) {
            for (int i = 0; i < feeds.size(); i++) if (feeds.get(i)[0].equalsIgnoreCase(cat)) c = i + 1;
        }
        if (c < 1 || c > feeds.size()) c = 1;
        String[] f = feeds.get(c - 1);
        List<NewsHandler.Item> items = news.items(f[1]);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        // 2013 apps (S4) read <results><results>news entries</results><results>image entries</results></results>
        // and match images to news by id; the 2012 apps (S3) take the image from the news entry itself.
        StringBuilder x = new StringBuilder(16384).append("<results><results>");
        StringBuilder imgs = new StringBuilder();
        for (NewsHandler.Item it : items) {
            String when = iso.format(new Date(it.ts > 0 ? it.ts : System.currentTimeMillis()));
            x.append("<entry>");
            tag(x, "title", it.title);
            x.append("<link href=\"").append(WeatherHandler.esc(NewsHandler.readerUrl(it.link))).append("\"/>");
            String id = it.link.length() > 0 ? it.link : it.title;
            tag(x, "id", id);
            tag(x, "published", when);
            String body = it.body.trim();
            tag(x, "summary", body.length() > 600 ? body.substring(0, 600) + "\u2026" : body);
            if (it.image.length() > 0) {
                String img = WeatherHandler.esc("http://" + NewsHandler.HOST + "/capi/ap/img?width=320&u="
                        + URLEncoder.encode(it.image, "UTF-8"));
                x.append("<content url=\"").append(img).append("\"/>");
                imgs.append("<entry>");
                tag(imgs, "id", id);
                imgs.append("<content url=\"").append(img).append("\"/></entry>");
            }
            tag(x, "date", when);
            tag(x, "credit", f[0]);
            x.append("</entry>");
        }
        x.append("</results><results>").append(imgs).append("</results></results>");
        return yqlRaw(x.toString(), items.size());
    }

    // ---- Finance ----

    private String post(String body) throws Exception {
        String type = attr(body, "query", "type");
        String inner;
        if ("getquotes".equals(type)) {
            String q = quotes(all(body, "symbol"));
            inner = "<list count=\"" + count(q, "<quote>") + "\">" + q + "</list>";
        } else if ("getsymbol".equals(type)) {
            String r = search(first(body, "phrase"));
            // 2010 parsers read quote/name,symbol,exchange; 2012 ones read result/...
            String legacy = r.replace("<result>", "<quote>").replace("</result>", "</quote>");
            inner = "<list count=\"" + count(r, "<result>") + "\">" + legacy + r + "</list>";
        } else if ("getchart".equals(type)) {
            inner = legacyChart(first(body, "symbol"), first(body, "range"));
        }
        else throw new java.io.IOException("unknown finance query " + type);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<response><result type=\"" + type + "\" timestamp=\""
                + System.currentTimeMillis() / 1000 + "\">" + inner + "</result></response>";
    }

    private String quotes(List<String> symbols) throws Exception {
        List<Future<String>> jobs = new ArrayList<Future<String>>();
        for (final String sym : symbols) {
            jobs.add(pool.submit(new Callable<String>() {
                public String call() {
                    try {
                        return quote(sym);
                    } catch (Exception e) {
                        Log.w(MiniHttp.TAG, "quote " + sym + " failed: " + e);
                        return null; // the widget keeps its stored values for missing symbols
                    }
                }
            }));
        }
        StringBuilder out = new StringBuilder();
        for (Future<String> f : jobs) {
            String q = f.get();
            if (q != null) out.append(q);
        }
        return out.toString();
    }

    private String quote(String sym) throws Exception {
        // Daily bars over 5 days: the intraday chart's previousClose can be far off.
        JSONObject r = chartResult(sym, "5d", "1d", QUOTE_CACHE_MS);
        JSONObject m = r.getJSONObject("meta");
        double price = m.getDouble("regularMarketPrice");
        double prev = previousClose(r, m.optLong("regularMarketTime", System.currentTimeMillis() / 1000), price);
        double change = price - prev, pct = prev == 0 ? 0 : change * 100 / prev;
        double open = last(r, "open", prev);
        // Echo the symbol as requested: Yahoo canonicalises some (USDEUR=X -> EUR=X) and the
        // widget matches rows by the symbol it asked for.
        String s = sym.toUpperCase(Locale.US);
        StringBuilder q = new StringBuilder("<quote>");
        int dp = sym.endsWith("=X") ? 4 : 2; // currency rates need four decimals
        tag(q, "symbol", s);
        // Yahoo pads some short names ("DAX                           P").
        String name = m.optString("shortName", "").replaceAll("\\s{2,}\\S?$", "").trim();
        tag(q, "name", name.length() > 0 ? name : m.optString("longName", s));
        tag(q, "exchange", m.optString("fullExchangeName", m.optString("exchangeName", "")));
        tag(q, "price", num(price, dp));
        tag(q, "change", signed(change, dp));
        tag(q, "chg_percent", signed(pct));
        tag(q, "day_high", num(m.optDouble("regularMarketDayHigh", price), dp));
        tag(q, "day_low", num(m.optDouble("regularMarketDayLow", price), dp));
        tag(q, "volume", String.valueOf(m.optLong("regularMarketVolume", 0)));
        tag(q, "prev_close", num(prev, dp));
        tag(q, "open", num(open, dp));
        tag(q, "market_cap", "N/A");
        tag(q, "eps_curr_year", "N/A");
        tag(q, "pe_ratio", "N/A");
        tag(q, "year_high", num(m.optDouble("fiftyTwoWeekHigh", price), dp));
        tag(q, "year_low", num(m.optDouble("fiftyTwoWeekLow", price), dp));
        tag(q, "details_url", "http://finance.yahoo.com/quote/" + URLEncoder.encode(s, "UTF-8"));
        tag(q, "ts", String.valueOf(m.optLong("regularMarketTime", System.currentTimeMillis() / 1000)));
        // 2010 (htc-style) names for the Galaxy S stock clock and Daily Briefing.
        tag(q, "changepercent", signed(pct));
        tag(q, "high", num(m.optDouble("regularMarketDayHigh", price), dp));
        tag(q, "low", num(m.optDouble("regularMarketDayLow", price), dp));
        tag(q, "link", "http://finance.yahoo.com/quote/" + URLEncoder.encode(s, "UTF-8"));
        tag(q, "marketcap", "N/A");
        tag(q, "peratio", "N/A");
        tag(q, "dividendyield", "N/A");
        tag(q, "status", "0");
        tag(q, "timestamp", String.valueOf(m.optLong("regularMarketTime", System.currentTimeMillis() / 1000)));
        return q.append("</quote>").toString();
    }

    private String search(String phrase) throws Exception {
        StringBuilder out = new StringBuilder();
        if (phrase.trim().length() == 0) return "";
        String url = SEARCH + "?q=" + URLEncoder.encode(phrase.trim(), "UTF-8")
                + "&quotesCount=15&newsCount=0&listsCount=0";
        JSONArray hits = new JSONObject(fetch(url, QUOTE_CACHE_MS)).optJSONArray("quotes");
        for (int i = 0; hits != null && i < hits.length(); i++) {
            JSONObject h = hits.getJSONObject(i);
            String sym = h.optString("symbol", "");
            if (sym.length() == 0) continue;
            out.append("<result>");
            tag(out, "name", h.optString("shortname", h.optString("longname", sym)));
            tag(out, "symbol", sym);
            tag(out, "exchange", h.optString("exchange", ""));
            tag(out, "exchange_name", h.optString("exchDisp", h.optString("exchange", "")));
            tag(out, "type", "ETF".equals(h.optString("quoteType")) ? "ETF" : "Stock");
            out.append("</result>");
        }
        return out.toString();
    }

    private String chart(String sym, String range) throws Exception {
        String[] yr = RANGES.get(range.toLowerCase(Locale.US));
        if (yr == null) yr = RANGES.get("1d");
        JSONObject r = chartResult(sym, yr[0], yr[1], "1d".equals(yr[0]) ? QUOTE_CACHE_MS : CHART_CACHE_MS);
        JSONArray ts = r.optJSONArray("timestamp");
        JSONObject ind = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0);
        JSONArray close = ind.optJSONArray("close"), high = ind.optJSONArray("high"), vol = ind.optJSONArray("volume");
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        StringBuilder out = new StringBuilder();
        for (int i = 0; ts != null && close != null && i < ts.length(); i++) {
            if (close.isNull(i)) continue; // Yahoo leaves gaps for empty intervals
            double c = close.getDouble(i);
            out.append("<value>");
            tag(out, "close", num(c));
            tag(out, "high", num(at(high, i, c)));
            tag(out, "time", String.valueOf(ts.getLong(i)));
            tag(out, "volume", String.valueOf(vol == null || vol.isNull(i) ? 0 : vol.getLong(i)));
            tag(out, "date", day.format(new Date(ts.getLong(i) * 1000)));
            out.append("</value>");
        }
        return out.toString();
    }

    /** getchart for the 2010 parsers: point elements with attributes, plus a meta block. */
    private String legacyChart(String sym, String range) throws Exception {
        String[] yr = RANGES.get(range.toLowerCase(Locale.US));
        if (yr == null) yr = RANGES.get("1d");
        JSONObject r = chartResult(sym, yr[0], yr[1], "1d".equals(yr[0]) ? QUOTE_CACHE_MS : CHART_CACHE_MS);
        JSONObject m = r.getJSONObject("meta");
        JSONArray ts = r.optJSONArray("timestamp");
        JSONObject ind = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0);
        JSONArray close = ind.optJSONArray("close"), open = ind.optJSONArray("open"),
                high = ind.optJSONArray("high"), low = ind.optJSONArray("low"), vol = ind.optJSONArray("volume");
        StringBuilder pts = new StringBuilder();
        int n = 0;
        for (int i = 0; ts != null && close != null && i < ts.length(); i++) {
            if (close.isNull(i)) continue;
            double c = close.getDouble(i);
            pts.append("<point timestamp=\"").append(ts.getLong(i))
                    .append("\" close=\"").append(num(c, 4))
                    .append("\" open=\"").append(num(at(open, i, c), 4))
                    .append("\" high=\"").append(num(at(high, i, c), 4))
                    .append("\" low=\"").append(num(at(low, i, c), 4))
                    .append("\" volume=\"").append(vol == null || vol.isNull(i) ? 0 : vol.getLong(i))
                    .append("\"/>");
            n++;
        }
        JSONObject reg = m.optJSONObject("currentTradingPeriod") == null ? null
                : m.getJSONObject("currentTradingPeriod").optJSONObject("regular");
        StringBuilder meta = new StringBuilder("<meta>");
        tag(meta, "symbol", sym.toUpperCase(Locale.US));
        tag(meta, "marketopen", String.valueOf(reg == null ? 0 : reg.optLong("start")));
        tag(meta, "marketclose", String.valueOf(reg == null ? 0 : reg.optLong("end")));
        tag(meta, "gmtoffset", String.valueOf(m.optLong("gmtoffset", 0)));
        meta.append("</meta>");
        return meta + "<list count=\"" + n + "\">" + pts + "</list>";
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }

    private JSONObject chartResult(String sym, String range, String interval, long maxAge) throws Exception {
        String url = CHART + URLEncoder.encode(sym, "UTF-8") + "?range=" + range + "&interval=" + interval
                + "&includePrePost=false";
        JSONArray res = new JSONObject(fetch(url, maxAge)).getJSONObject("chart").optJSONArray("result");
        if (res == null || res.length() == 0) throw new java.io.IOException("no data for " + sym);
        return res.getJSONObject(0);
    }

    private String fetch(String url, long maxAge) throws Exception {
        synchronized (cache) {
            Object[] hit = cache.get(url);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < maxAge) return (String) hit[1];
        }
        String body = Net.getString(url);
        synchronized (cache) {
            if (cache.size() > 200) cache.clear();
            cache.put(url, new Object[]{System.currentTimeMillis(), body});
        }
        return body;
    }

    // ---- helpers ----

    private static String yql(String results) {
        return yqlRaw("<results>" + results + "</results>", 0);
    }

    private static String yqlRaw(String inner, int count) {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<query xmlns:yahoo=\"http://www.yahooapis.com/v1/base.rng\""
                + " yahoo:count=\"" + count + "\" yahoo:created=\"" + iso.format(new Date()) + "\" yahoo:lang=\"en-US\">"
                + inner + "</query>";
    }

    /** The value in {@code name = 'value'} (or "value") from a YQL where clause. */
    private static String clause(String q, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE).matcher(q);
        return m.find() ? m.group(1).trim() : "";
    }

    /** Every quoted value, de-duplicated: {@code symbol in ('A','B')} or {@code symbol='A'}. */
    private static List<String> quoted(String s) {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(s);
        while (m.find()) {
            String v = m.group(1).trim();
            if (v.length() > 0 && !out.contains(v)) out.add(v);
        }
        return out;
    }

    private static String attr(String xml, String tag, String name) {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*\\b" + name + "=['\"]([^'\"]*)['\"]").matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    private static List<String> all(String xml, String tag) {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String v = m.group(1).replace("<![CDATA[", "").replace("]]>", "").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&").trim();
            if (v.length() > 0 && !out.contains(v)) out.add(v);
        }
        return out;
    }

    private static String first(String xml, String tag) {
        List<String> v = all(xml, tag);
        return v.isEmpty() ? "" : v.get(0);
    }

    /** Close of the last daily bar that ends before the trading day of {@code now} (epoch s). */
    private static double previousClose(JSONObject r, long now, double fallback) {
        try {
            JSONArray ts = r.getJSONArray("timestamp");
            JSONArray close = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close");
            long gmt = r.getJSONObject("meta").optLong("gmtoffset", 0);
            long today = (now + gmt) / 86400;
            for (int i = ts.length() - 1; i >= 0; i--) {
                if (close.isNull(i)) continue;
                if ((ts.getLong(i) + gmt) / 86400 < today) return close.getDouble(i);
            }
            JSONObject m = r.getJSONObject("meta");
            return m.optDouble("chartPreviousClose", fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double last(JSONObject r, String field, double fallback) {
        try {
            JSONArray a = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).optJSONArray(field);
            for (int i = a == null ? -1 : a.length() - 1; i >= 0; i--) if (!a.isNull(i)) return a.getDouble(i);
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static double at(JSONArray a, int i, double fallback) {
        return a == null || i >= a.length() || a.isNull(i) ? fallback : a.optDouble(i, fallback);
    }

    private static String num(double d) {
        return String.format(Locale.US, "%.2f", d);
    }

    private static String signed(double d) {
        return signed(d, 2);
    }

    private static String num(double d, int dp) {
        return String.format(Locale.US, "%." + dp + "f", d);
    }

    private static String signed(double d, int dp) {
        return (d >= 0 ? "+" : "") + num(d, dp);
    }

    private static String or(String v, String def) {
        return v.length() > 0 ? v : def;
    }

    private static void tag(StringBuilder sb, String name, String value) {
        sb.append('<').append(name).append('>').append(WeatherHandler.esc(value == null ? "" : value))
                .append("</").append(name).append('>');
    }
}
