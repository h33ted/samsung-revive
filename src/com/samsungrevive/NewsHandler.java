package com.samsungrevive;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stands in for samsungrss.vrvm.com, the Verve Wireless AP feed behind the Galaxy S2's
 * "AP News" widget (com.sec.android.widgetapp.apnews):
 *  - /capi/ap/hierarchy: OPML, one <outline name id adCategoryId partnerModuleId xmlUrl> per
 *    category (APNewsHeadXmlParse; the ids are parsed as integers).
 *  - /capi/ap/feed?c=N: RSS 2.0 items with title, link, pubDate ("EEE, dd MMM yyyy HH:mm:ss 'GMT'"),
 *    media:content url (the widget appends &width=256) and content:encoded with <p> paragraphs
 *    (APNewsXmlParse).
 *  - /capi/ap/img?u=..: the article image, fetched and scaled to the requested width.
 * AP no longer publishes RSS, so the categories are ordinary feeds the user picks (Prefs).
 */
final class NewsHandler implements MiniHttp.Handler {
    static final String HOST = "samsungrss.vrvm.com";
    /** Galaxy S Daily Briefing reads its AP headlines from mnndev.vrvm.com/capi/ap/contentlist/db_36972. */
    static final String DEV_HOST = "mnndev.vrvm.com";
    static final String DEFAULT_FEEDS =
            "Top Stories|https://feeds.bbci.co.uk/news/rss.xml\n"
            + "World|https://feeds.bbci.co.uk/news/world/rss.xml\n"
            + "Business|https://feeds.bbci.co.uk/news/business/rss.xml\n"
            + "Technology|https://feeds.bbci.co.uk/news/technology/rss.xml\n"
            + "Science|https://feeds.bbci.co.uk/news/science_and_environment/rss.xml\n"
            + "Health|https://feeds.bbci.co.uk/news/health/rss.xml\n"
            + "Entertainment|https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml\n"
            + "Sport|https://feeds.bbci.co.uk/sport/rss.xml\n"
            + "Austria|https://rss.orf.at/news.xml";

    private static final long FEED_TTL_MS = 15 * 60 * 1000L;
    private static final int MAX_ITEMS = 20;

    static final class Item {
        String title = "", link = "", body = "", image = "";
        long ts;
    }

    private final Context ctx;
    private final Map<String, Object[]> cache = new HashMap<String, Object[]>();
    private final java.util.Set<String> refreshing = new java.util.HashSet<String>();
    /** Scaled images by URL + width; the widget re-requests them on every refresh. */
    private final Map<String, byte[]> images = new java.util.LinkedHashMap<String, byte[]>(64, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
            return size() > 80;
        }
    };

    NewsHandler(Context ctx) {
        this.ctx = ctx;
        // An upstream fetch over software TLS 1.2 takes ~20 s on a Galaxy S2, longer than the
        // widget waits, so keep every category warm in the background and answer from cache.
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    warm();
                    try {
                        Thread.sleep(FEED_TTL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "news-warm");
        t.setDaemon(true);
        t.start();
    }

    /** Fetches every configured category that is missing or stale, one after another. */
    void warm() {
        for (String[] f : Prefs.newsFeeds(ctx)) refresh(f[1], false);
    }

    public MiniHttp.Response handle(MiniHttp.Request req) throws Exception {
        String p = req.path.toLowerCase(Locale.US);
        if (p.endsWith("/hierarchy")) return hierarchy();
        if (p.endsWith("/feed")) return feed(req);
        if (p.contains("/contentlist")) return feed(1);
        if (p.endsWith("/img")) return image(req);
        if (p.endsWith("/read")) return read(req);
        Log.w(MiniHttp.TAG, "news: unhandled path " + req.path);
        return MiniHttp.Response.notFound();
    }

    private MiniHttp.Response hierarchy() {
        new Thread(new Runnable() {
            public void run() {
                warm();
            }
        }, "news-warm-now").start();
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<opml version=\"1.0\"><head><title>AP</title></head><body>");
        List<String[]> feeds = Prefs.newsFeeds(ctx);
        for (int i = 0; i < feeds.size(); i++) {
            int id = i + 1;
            x.append("<outline text=\"").append(WeatherHandler.esc(feeds.get(i)[0]))
                    .append("\" name=\"").append(WeatherHandler.esc(feeds.get(i)[0]))
                    .append("\" id=\"").append(id).append("\" adCategoryId=\"").append(id)
                    .append("\" partnerModuleId=\"").append(id)
                    .append("\" headline=\"").append(i == 0 ? "true" : "false")
                    .append("\" type=\"rss\" xmlUrl=\"http://").append(HOST).append("/capi/ap/feed?c=").append(id)
                    .append("\"/>");
        }
        x.append("</body></opml>");
        return MiniHttp.Response.text(200, "text/xml; charset=utf-8", x.toString());
    }

    private MiniHttp.Response feed(MiniHttp.Request req) throws Exception {
        try {
            return feed(Integer.parseInt(req.q("c")));
        } catch (NumberFormatException e) {
            return MiniHttp.Response.notFound();
        }
    }

    private MiniHttp.Response feed(int c) throws Exception {
        List<String[]> feeds = Prefs.newsFeeds(ctx);
        if (c < 1 || c > feeds.size()) return MiniHttp.Response.notFound();
        String[] f = feeds.get(c - 1);
        List<Item> items = items(f[1]);

        SimpleDateFormat date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        date.setTimeZone(TimeZone.getTimeZone("GMT"));
        StringBuilder x = new StringBuilder(16384);
        x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<rss version=\"2.0\"")
                .append(" xmlns:media=\"http://search.yahoo.com/mrss/\"")
                .append(" xmlns:content=\"http://purl.org/rss/1.0/modules/content/\"><channel>");
        tag(x, "title", f[0]);
        tag(x, "link", "http://" + HOST + "/");
        tag(x, "description", f[0]);
        for (Item it : items) {
            x.append("<item>");
            tag(x, "title", it.title);
            tag(x, "link", it.link);
            tag(x, "pubDate", date.format(new Date(it.ts > 0 ? it.ts : System.currentTimeMillis())));
            if (it.image.length() > 0) {
                // The widget appends "&width=256", so the URL must already carry a query.
                x.append("<media:content medium=\"image\" url=\"").append(WeatherHandler.esc("http://" + HOST
                        + "/capi/ap/img?u=" + URLEncoder.encode(it.image, "UTF-8"))).append("\"/>");
            }
            StringBuilder body = new StringBuilder();
            for (String para : it.body.split("\n+")) {
                if (para.trim().length() > 0) body.append("<p>").append(para.trim()).append("</p>");
            }
            body.append("<p>Source: ").append(f[0]).append(". Full story: ").append(it.link).append("</p>");
            x.append("<content:encoded>").append(WeatherHandler.esc(body.toString())).append("</content:encoded>");
            x.append("</item>");
        }
        x.append("</channel></rss>");
        return MiniHttp.Response.text(200, "text/xml; charset=utf-8", x.toString());
    }

    private MiniHttp.Response image(MiniHttp.Request req) throws Exception {
        String url = req.q("u");
        if (!url.startsWith("http")) return MiniHttp.Response.notFound();
        int width = 256;
        try {
            width = Math.max(64, Math.min(1024, Integer.parseInt(req.q("width"))));
        } catch (NumberFormatException ignored) {
        }
        String key = width + " " + url;
        synchronized (images) {
            byte[] hit = images.get(key);
            if (hit != null) return new MiniHttp.Response(200, "image/jpeg", hit);
        }
        byte[] scaled = scale(Net.get(url), width);
        synchronized (images) {
            images.put(key, scaled);
        }
        return new MiniHttp.Response(200, "image/jpeg", scaled);
    }

    /** Plain-HTTP article reader for browsers that can no longer reach the publisher over TLS. */
    static String readerUrl(String link) {
        try {
            return "http://" + HOST + "/capi/ap/read?u=" + URLEncoder.encode(link, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return link;
        }
    }

    private final Map<String, String> pages = new java.util.LinkedHashMap<String, String>(16, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > 30;
        }
    };

    private static final Pattern META = Pattern.compile(
            "<meta[^>]+(?:property|name)=[\"'](og:title|og:image|og:site_name)[\"'][^>]*content=[\"']([^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PARA = Pattern.compile("<p\\b[^>]*>(.*?)</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NOISE = Pattern.compile("<(script|style|nav|header|footer|aside|figure)\\b.*?</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private MiniHttp.Response read(MiniHttp.Request req) throws Exception {
        String url = req.q("u");
        if (!url.startsWith("http")) return MiniHttp.Response.notFound();
        String page;
        synchronized (pages) {
            page = pages.get(url);
        }
        if (page == null) {
            String html = new String(Net.get(url), "UTF-8");
            String title = "", image = "", site = "";
            Matcher m = META.matcher(html);
            while (m.find()) {
                String k = m.group(1).toLowerCase(Locale.US), v = clean(text(m.group(2)));
                if (k.equals("og:title") && title.length() == 0) title = v;
                else if (k.equals("og:image") && image.length() == 0) image = m.group(2).replace("&amp;", "&");
                else if (k.equals("og:site_name") && site.length() == 0) site = v;
            }
            String body = html;
            int a = body.toLowerCase(Locale.US).indexOf("<article");
            if (a >= 0) body = body.substring(a);
            body = NOISE.matcher(body).replaceAll(" ");
            StringBuilder paras = new StringBuilder();
            Matcher pm = PARA.matcher(body);
            int n = 0;
            while (pm.find() && n < 60) {
                String t = clean(text(pm.group(1))).trim();
                if (t.length() < 25) continue; // skip bylines, captions and buttons
                paras.append("<p>").append(WeatherHandler.esc(t)).append("</p>\n");
                n++;
            }
            StringBuilder x = new StringBuilder(8192);
            x.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                    .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                    .append("<title>").append(WeatherHandler.esc(title)).append("</title><style>")
                    .append("body{font-family:sans-serif;margin:12px;line-height:1.45;color:#222}")
                    .append("h1{font-size:1.3em;margin:0 0 8px}img{max-width:100%;height:auto}")
                    .append(".src{color:#777;font-size:.85em}</style></head><body>");
            x.append("<h1>").append(WeatherHandler.esc(title)).append("</h1>");
            if (site.length() > 0) x.append("<div class=\"src\">").append(WeatherHandler.esc(site)).append("</div>");
            if (image.startsWith("http")) {
                x.append("<p><img src=\"").append(WeatherHandler.esc("http://" + HOST + "/capi/ap/img?width=480&u="
                        + URLEncoder.encode(image, "UTF-8"))).append("\"></p>");
            }
            x.append(paras.length() > 0 ? paras.toString() : "<p>The article text could not be extracted.</p>");
            x.append("<p class=\"src\">Original: ").append(WeatherHandler.esc(url)).append("</p></body></html>");
            page = x.toString();
            synchronized (pages) {
                pages.put(url, page);
            }
        }
        return MiniHttp.Response.text(200, "text/html; charset=utf-8", page);
    }

    private static byte[] scale(byte[] raw, int width) {
        // Old phones keep these as blobs in the widget's database: scale down and re-encode.
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (b == null) return raw;
        if (b.getWidth() > width) {
            Bitmap s = Bitmap.createScaledBitmap(b, width, Math.max(1, b.getHeight() * width / b.getWidth()), true);
            b.recycle();
            b = s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        b.recycle();
        return out.toByteArray();
    }

    // ---- RSS / Atom ------------------------------------------------------------------------

    /** Cached items, even if stale (refreshed in the background); fetched now only if never seen. */
    List<Item> items(final String url) throws Exception {
        synchronized (cache) {
            Object[] hit = cache.get(url);
            if (hit != null) {
                if (System.currentTimeMillis() - (Long) hit[0] >= FEED_TTL_MS) {
                    new Thread(new Runnable() {
                        public void run() {
                            refresh(url, false);
                        }
                    }, "news-refresh").start();
                }
                @SuppressWarnings("unchecked") List<Item> l = (List<Item>) hit[1];
                return l;
            }
        }
        refresh(url, true);
        synchronized (cache) {
            Object[] hit = cache.get(url);
            if (hit == null) throw new java.io.IOException("feed unavailable: " + url);
            @SuppressWarnings("unchecked") List<Item> l = (List<Item>) hit[1];
            return l;
        }
    }

    /** One fetch per URL at a time; others wait for it (wait=true) or skip (wait=false). */
    private void refresh(String url, boolean wait) {
        synchronized (refreshing) {
            Object[] hit;
            synchronized (cache) {
                hit = cache.get(url);
            }
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < FEED_TTL_MS) return;
            while (refreshing.contains(url)) {
                if (!wait) return;
                try {
                    refreshing.wait(30000);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (cache) {
                    if (cache.containsKey(url)) return;
                }
            }
            refreshing.add(url);
        }
        try {
            List<Item> items = parse(Net.get(url));
            synchronized (cache) {
                cache.put(url, new Object[]{System.currentTimeMillis(), items});
            }
        } catch (Exception e) {
            Log.w(MiniHttp.TAG, "news feed " + url + " failed: " + e);
        } finally {
            synchronized (refreshing) {
                refreshing.remove(url);
                refreshing.notifyAll();
            }
        }
    }

    private static final Pattern IMG = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    static List<Item> parse(byte[] xml) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new ByteArrayInputStream(xml), null);
        List<Item> out = new ArrayList<Item>();
        Item cur = null;
        String summary = "", full = "";
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName().toLowerCase(Locale.US);
                if (n.equals("item") || n.equals("entry")) {
                    cur = new Item();
                    summary = full = "";
                } else if (cur != null) {
                    if (n.equals("title")) cur.title = clean(p.nextText());
                    else if (n.equals("link")) {
                        String href = p.getAttributeValue(null, "href");
                        String rel = p.getAttributeValue(null, "rel");
                        if (href != null) {
                            if (rel == null || rel.equals("alternate")) cur.link = href.trim();
                        } else cur.link = p.nextText().trim();
                    } else if (n.equals("pubdate") || n.equals("published") || n.equals("updated") || n.equals("dc:date")) {
                        if (cur.ts == 0) cur.ts = time(p.nextText().trim());
                    } else if (n.equals("description") || n.equals("summary")) summary = p.nextText();
                    else if (n.equals("content:encoded") || n.equals("content")) {
                        String url = p.getAttributeValue(null, "url");
                        if (url != null) {
                            if (cur.image.length() == 0) cur.image = url;
                        } else full = p.nextText();
                    } else if (n.equals("media:thumbnail") || n.equals("media:content")) {
                        String url = p.getAttributeValue(null, "url");
                        if (url != null && cur.image.length() == 0) cur.image = url;
                    } else if (n.equals("enclosure")) {
                        String url = p.getAttributeValue(null, "url");
                        String type = p.getAttributeValue(null, "type");
                        if (url != null && cur.image.length() == 0 && (type == null || type.startsWith("image"))) cur.image = url;
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && cur != null) {
                String n = p.getName().toLowerCase(Locale.US);
                if (n.equals("item") || n.equals("entry")) {
                    String html = full.length() > summary.length() ? full : summary;
                    if (cur.image.length() == 0) {
                        Matcher m = IMG.matcher(html);
                        if (m.find()) cur.image = m.group(1);
                    }
                    cur.body = text(html);
                    if (cur.title.length() > 0) out.add(cur);
                    cur = null;
                    if (out.size() >= MAX_ITEMS) break;
                }
            }
        }
        return out;
    }

    /** HTML to plain paragraphs separated by newlines. */
    private static String text(String html) {
        String s = html.replaceAll("(?i)<\\s*(br|/p|/div|/li|/h[1-6])\\s*/?>", "\n");
        s = TAGS.matcher(s).replaceAll("");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" *\n[ \n]*", "\n").trim();
    }

    private static String clean(String s) {
        return text(s).replace('\n', ' ');
    }

    private static long time(String s) {
        String[] fmts = {"EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMM yyyy HH:mm:ss Z",
                "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"};
        // ISO 8601 offsets like +01:00 -> +0100 for SimpleDateFormat's Z.
        String iso = s.replaceAll("([+-]\\d\\d):(\\d\\d)$", "$1$2");
        for (String f : fmts) {
            SimpleDateFormat d = new SimpleDateFormat(f, Locale.US);
            if (f.endsWith("'Z'")) d.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date r = d.parse(f.startsWith("yyyy") ? iso : s, new ParsePosition(0));
            if (r != null) return r.getTime();
        }
        return 0;
    }

    private static void tag(StringBuilder x, String name, String value) {
        x.append('<').append(name).append('>').append(WeatherHandler.esc(value)).append("</").append(name).append('>');
    }
}
