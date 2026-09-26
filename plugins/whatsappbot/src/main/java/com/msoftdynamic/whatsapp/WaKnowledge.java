package com.msoftdynamic.whatsapp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.text.StringEscapeUtils;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * The AI agent's knowledge: turn files, web pages and typed text into chunks, and find the chunks
 * that best match a customer's question (keyword ranking, works for English, Hindi and mixed text).
 */
public final class WaKnowledge {
    private static final String MODULE = WaKnowledge.class.getName();
    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_PAGE_BYTES = 3 * 1024 * 1024;
    private static final int CHUNK = 1200;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private WaKnowledge() { }

    /** Thrown with a message that can be shown to the user. */
    public static final class KbException extends Exception {
        private static final long serialVersionUID = 1L;
        public KbException(String msg) {
            super(msg);
        }
    }

    public static int maxSources() {
        return WaUtil.propInt("ai.kb.max.sources", 50);
    }

    public static int maxChars() {
        return WaUtil.propInt("ai.kb.max.chars", 1_500_000);
    }

    // ================================================================== text extraction
    /** Text of an uploaded file: PDF, Word (.docx), text, CSV, Markdown or HTML. */
    public static String extractFile(String fileName, byte[] data) throws KbException {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (data == null || data.length == 0) {
            throw new KbException("The file is empty.");
        }
        if (data.length > MAX_FILE_BYTES) {
            throw new KbException("The file is too big. The limit is 10 MB.");
        }
        if (name.endsWith(".pdf") || startsWith(data, "%PDF")) {
            return pdfText(data);
        }
        if (name.endsWith(".docx")) {
            return docxText(data);
        }
        if (name.endsWith(".doc") || name.endsWith(".xls") || name.endsWith(".xlsx") || name.endsWith(".ppt") || name.endsWith(".pptx")) {
            throw new KbException("This file type is not supported. Save it as PDF, Word (.docx) or text and upload again.");
        }
        String text = decode(data, null);
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return htmlText(text, null).text;
        }
        if (text.indexOf('\u0000') >= 0) {
            throw new KbException("This doesn't look like a text file. Upload a PDF, Word (.docx), TXT or CSV file.");
        }
        return clean(text);
    }

    private static boolean startsWith(byte[] d, String sig) {
        byte[] s = sig.getBytes(StandardCharsets.US_ASCII);
        if (d.length < s.length) {
            return false;
        }
        for (int i = 0; i < s.length; i++) {
            if (d[i] != s[i]) {
                return false;
            }
        }
        return true;
    }

    static String pdfText(byte[] data) throws KbException {
        try (PDDocument doc = Loader.loadPDF(data)) {
            if (doc.isEncrypted() && !doc.getCurrentAccessPermission().canExtractContent()) {
                throw new KbException("This PDF is password protected. Remove the password and upload it again.");
            }
            PDFTextStripper st = new PDFTextStripper();
            st.setSortByPosition(true);
            st.setEndPage(Math.min(doc.getNumberOfPages(), 500));
            String text = clean(st.getText(doc));
            if (text.replaceAll("\\s", "").length() < 20) {
                throw new KbException("No text found in this PDF. It is probably a scanned image; upload a PDF with selectable text or paste the text instead.");
            }
            return text;
        } catch (KbException e) {
            throw e;
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new KbException("This PDF is password protected. Remove the password and upload it again.");
        } catch (Exception e) {
            Debug.logWarning(e, "PDF read failed", MODULE);
            throw new KbException("This PDF could not be read. Try saving it again as PDF, or upload it as Word or text.");
        }
    }

    static String docxText(byte[] data) throws KbException {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if ("word/document.xml".equals(e.getName())) {
                    String xml = new String(readLimited(zin, 40 * 1024 * 1024), StandardCharsets.UTF_8);
                    xml = xml.replaceAll("</w:p>", "\n").replaceAll("<w:tab/>", "\t").replaceAll("<w:br[^>]*/>", "\n")
                            .replaceAll("</w:tc>", " | ").replaceAll("<[^>]+>", "");
                    String text = clean(StringEscapeUtils.unescapeXml(xml));
                    if (text.isEmpty()) {
                        throw new KbException("No text found in this Word file.");
                    }
                    return text;
                }
            }
        } catch (KbException e) {
            throw e;
        } catch (Exception e) {
            Debug.logWarning(e, "DOCX read failed", MODULE);
        }
        throw new KbException("This Word file could not be read. Save it as .docx or PDF and try again.");
    }

    private static byte[] readLimited(InputStream in, int max) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) {
                throw new java.io.IOException("too large");
            }
        }
        return out.toByteArray();
    }

    private static String decode(byte[] data, String charset) {
        if (data.length >= 3 && (data[0] & 0xff) == 0xEF && (data[1] & 0xff) == 0xBB && (data[2] & 0xff) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        Charset cs = StandardCharsets.UTF_8;
        try {
            if (charset != null) {
                cs = Charset.forName(charset);
            }
        } catch (Exception ignore) {
            // unknown charset: UTF-8
        }
        return new String(data, cs);
    }

    /** Normalise whitespace, keep paragraphs. */
    static String clean(String t) {
        if (t == null) {
            return "";
        }
        String s = t.replace("\r\n", "\n").replace('\r', '\n').replace('\u00a0', ' ').replaceAll("[\\t\\x0B\\f ]+", " ");
        s = s.replaceAll(" *\n *", "\n").replaceAll("\n{3,}", "\n\n");
        return s.trim();
    }

    // ------------------------------------------------------------------ HTML
    static final class Page {
        String title;
        String text;
        List<String> links = new ArrayList<>();
    }

    private static final Pattern DROP = Pattern.compile("(?is)<(script|style|noscript|svg|template|iframe|head)\\b.*?</\\1>");
    private static final Pattern NAVS = Pattern.compile("(?is)<(nav|footer)\\b.*?</\\1>");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern HREF = Pattern.compile("(?is)<a\\b[^>]*?href\\s*=\\s*[\"']([^\"'#]+)");

    static Page htmlText(String html, URI base) {
        Page p = new Page();
        Matcher tm = TITLE.matcher(html);
        if (tm.find()) {
            p.title = clean(StringEscapeUtils.unescapeHtml4(tm.group(1).replaceAll("<[^>]+>", "")));
        }
        if (base != null) {
            Matcher hm = HREF.matcher(html);
            while (hm.find() && p.links.size() < 500) {
                try {
                    URI u = base.resolve(StringEscapeUtils.unescapeHtml4(hm.group(1).trim()));
                    p.links.add(new URI(u.getScheme(), u.getAuthority(), u.getPath(), u.getQuery(), null).toString());
                } catch (Exception ignore) {
                    // skip bad links
                }
            }
        }
        String s = DROP.matcher(html).replaceAll(" ");
        s = s.replaceAll("(?s)<!--.*?-->", " ");
        String withoutNav = NAVS.matcher(s).replaceAll(" ");
        if (withoutNav.replaceAll("<[^>]+>", "").trim().length() > 200) {
            s = withoutNav;
        }
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(p|div|h[1-6]|li|tr|section|article|table|ul|ol|header|blockquote)>", "\n")
                .replaceAll("(?i)<li[^>]*>", "\n• ").replaceAll("(?i)</t[dh]>", " | ").replaceAll("<[^>]+>", " ");
        p.text = clean(StringEscapeUtils.unescapeHtml4(s));
        return p;
    }

    // ------------------------------------------------------------------ web fetch (SSRF safe)
    /** Only public http(s) hosts on ports 80/443 (sandbox tests may allow private hosts). */
    static URI checkUrl(String raw) throws KbException {
        URI u;
        try {
            String t = raw == null ? "" : raw.trim();
            if (t.matches("(?i)^[a-z][a-z0-9+.-]*://.*") && !t.matches("(?i)https?://.*")) {
                throw new KbException("Use a web address starting with https://");
            }
            if (!t.matches("(?i)https?://.*")) {
                t = "https://" + t;
            }
            u = new URI(t).normalize();
        } catch (KbException e) {
            throw e;
        } catch (Exception e) {
            throw new KbException("That link doesn't look right. Use a full address like https://www.example.com/faq");
        }
        String host = u.getHost();
        if (host == null || !("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme()))) {
            throw new KbException("Use a web address starting with https://");
        }
        boolean allowPrivate = WaUtil.propTrue("ai.kb.allow.private.hosts");
        int port = u.getPort();
        if (port != -1 && port != 80 && port != 443 && !allowPrivate) {
            throw new KbException("Only normal web addresses (port 80 or 443) can be read.");
        }
        if (u.getUserInfo() != null) {
            throw new KbException("Links with a user name or password can't be read.");
        }
        if (!allowPrivate) {
            try {
                for (InetAddress a : InetAddress.getAllByName(IDN.toASCII(host))) {
                    if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                            || a.isMulticastAddress() || isPrivateV6(a) || isCgnat(a)) {
                        throw new KbException("This address points to a private network and can't be read.");
                    }
                }
            } catch (KbException e) {
                throw e;
            } catch (Exception e) {
                throw new KbException("The website " + host + " was not found. Check the link.");
            }
        }
        return u;
    }

    private static boolean isPrivateV6(InetAddress a) {
        if (!(a instanceof Inet6Address)) {
            return false;
        }
        byte b = a.getAddress()[0];
        return (b & 0xfe) == 0xfc; // fc00::/7 unique local
    }

    private static boolean isCgnat(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // 100.64.0.0/10
    }

    static final class Fetched {
        URI finalUri;
        String contentType;
        byte[] body;
    }

    static Fetched fetch(String url) throws KbException {
        URI u = checkUrl(url);
        for (int hop = 0; hop < 5; hop++) {
            HttpRequest req = HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (compatible; FloChatBot/1.0; +https://" + WaUtil.prop("brand.domain", "flolink.ai") + ")")
                    .header("Accept", "text/html,application/xhtml+xml,text/plain,application/pdf;q=0.9,*/*;q=0.5").GET().build();
            HttpResponse<InputStream> res;
            try {
                res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (java.net.http.HttpTimeoutException e) {
                throw new KbException("The website took too long to answer.");
            } catch (Exception e) {
                throw new KbException("Could not open " + u.getHost() + ". Check the link and that the site is online.");
            }
            int code = res.statusCode();
            if (code >= 300 && code < 400) {
                String loc = res.headers().firstValue("Location").orElse(null);
                closeQuietly(res.body());
                if (loc == null) {
                    throw new KbException("The website redirected without an address.");
                }
                u = checkUrl(u.resolve(loc).toString());
                continue;
            }
            if (code == 401 || code == 403) {
                closeQuietly(res.body());
                throw new KbException("The website refused access (" + code + "). Pages behind a login can't be read.");
            }
            if (code / 100 != 2) {
                closeQuietly(res.body());
                throw new KbException("The website answered with error " + code + ".");
            }
            Fetched f = new Fetched();
            f.finalUri = u;
            f.contentType = res.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            boolean pdf = f.contentType.contains("pdf");
            try (InputStream in = res.body()) {
                f.body = readLimited(in, pdf ? MAX_FILE_BYTES : MAX_PAGE_BYTES);
            } catch (Exception e) {
                throw new KbException("The page is too big to read.");
            }
            return f;
        }
        throw new KbException("The website redirected too many times.");
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (Exception ignore) {
            // nothing
        }
    }

    static String charsetOf(String contentType) {
        Matcher m = Pattern.compile("charset=([\\w-]+)").matcher(contentType == null ? "" : contentType);
        return m.find() ? m.group(1) : null;
    }

    /** Read a page (and optionally more pages of the same site). Returns [title, text, pages]. */
    static Object[] readSite(String url, int maxPages) throws KbException {
        Fetched first = fetch(url);
        String host = first.finalUri.getHost();
        StringBuilder all = new StringBuilder();
        String title = null;
        int pages = 0;
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(key(first.finalUri.toString()));
        Fetched cur = first;
        long deadline = System.currentTimeMillis() + WaUtil.propInt("ai.kb.crawl.seconds", 90) * 1000L;
        while (cur != null) {
            String piece;
            if (cur.contentType.contains("pdf") || startsWith(cur.body, "%PDF")) {
                piece = pdfText(cur.body);
                if (title == null) {
                    title = fileNameOf(cur.finalUri);
                }
            } else if (cur.contentType.contains("html") || cur.contentType.isEmpty() || cur.contentType.contains("xml")) {
                Page p = htmlText(decode(cur.body, charsetOf(cur.contentType)), cur.finalUri);
                piece = p.text;
                if (title == null) {
                    title = p.title;
                }
                for (String l : p.links) {
                    try {
                        URI lu = new URI(l);
                        String path = lu.getPath() == null ? "" : lu.getPath().toLowerCase(Locale.ROOT);
                        if (host.equalsIgnoreCase(lu.getHost()) && !path.matches(".*\\.(jpg|jpeg|png|gif|webp|svg|zip|mp4|mp3|css|js|ico|xml|json)$")
                                && seen.add(key(l))) {
                            queue.add(l);
                        }
                    } catch (Exception ignore) {
                        // skip
                    }
                }
            } else if (cur.contentType.startsWith("text/")) {
                piece = clean(decode(cur.body, charsetOf(cur.contentType)));
            } else {
                if (pages == 0) {
                    throw new KbException("This link is not a web page or PDF.");
                }
                piece = "";
            }
            if (!piece.isBlank()) {
                pages++;
                if (all.length() > 0) {
                    all.append("\n\n");
                }
                all.append("## ").append(cur.finalUri.getPath() == null || cur.finalUri.getPath().isEmpty() ? "/" : cur.finalUri.getPath()).append("\n").append(piece);
            }
            cur = null;
            while (pages < maxPages && !queue.isEmpty() && System.currentTimeMillis() < deadline && all.length() < maxChars()) {
                try {
                    cur = fetch(queue.poll());
                    break;
                } catch (KbException e) {
                    Debug.logInfo("Skipped page: " + e.getMessage(), MODULE);
                }
            }
        }
        if (all.toString().replaceAll("\\s", "").length() < 20) {
            throw new KbException("No readable text on this page. If the site is built with JavaScript only, copy the text and add it as a note instead.");
        }
        return new Object[] {title, all.toString(), pages};
    }

    private static String key(String u) {
        return u.replaceAll("/+$", "").replaceFirst("(?i)^https?://(www\\.)?", "").toLowerCase(Locale.ROOT);
    }

    private static String fileNameOf(URI u) {
        String p = u.getPath() == null ? "" : u.getPath();
        String n = p.substring(p.lastIndexOf('/') + 1);
        return n.isEmpty() ? u.getHost() : n;
    }

    // ================================================================== storing
    /** Split text into ~1200 character chunks on paragraph/sentence boundaries, carrying the latest heading. */
    static List<String[]> chunk(String text) {
        List<String[]> out = new ArrayList<>();
        String heading = "";
        StringBuilder cur = new StringBuilder();
        String curHeading = "";
        for (String para : text.split("\n\\s*\n|\n(?=## )")) {
            String p = para.trim();
            if (p.isEmpty()) {
                continue;
            }
            String firstLine = p.split("\n", 2)[0];
            if (firstLine.startsWith("## ") || (firstLine.length() < 80 && !firstLine.endsWith(".") && p.contains("\n") && firstLine.equals(firstLine.trim()))) {
                heading = firstLine.replaceFirst("^## ", "");
            }
            while (p.length() > CHUNK) {
                int cut = p.lastIndexOf(". ", CHUNK);
                if (cut < CHUNK / 2) {
                    cut = p.lastIndexOf(' ', CHUNK);
                }
                if (cut < CHUNK / 2) {
                    cut = CHUNK;
                }
                flush(out, cur, curHeading);
                out.add(new String[] {heading, p.substring(0, cut + 1).trim()});
                p = p.substring(cut + 1).trim();
            }
            if (cur.length() + p.length() > CHUNK) {
                flush(out, cur, curHeading);
            }
            if (cur.length() == 0) {
                curHeading = heading;
            }
            cur.append(cur.length() == 0 ? "" : "\n\n").append(p);
        }
        flush(out, cur, curHeading);
        return out;
    }

    private static void flush(List<String[]> out, StringBuilder cur, String heading) {
        if (cur.length() > 0) {
            out.add(new String[] {heading, cur.toString()});
            cur.setLength(0);
        }
    }

    /** Replace a source's chunks with this text and mark it READY. */
    static void store(Delegator delegator, GenericValue src, String title, String text, int pages) throws GenericEntityException, KbException {
        String tenantId = src.getString("tenantId");
        long others = 0;
        for (GenericValue s : EntityQuery.use(delegator).select("sourceId", "charCount").from("WaKbSource").where("tenantId", tenantId).queryList()) {
            if (!s.getString("sourceId").equals(src.getString("sourceId")) && s.get("charCount") != null) {
                others += s.getLong("charCount");
            }
        }
        if (others + text.length() > maxChars()) {
            throw new KbException("Your knowledge is full (" + (maxChars() / 1000) + "k characters). Remove a source or upload a shorter file.");
        }
        delegator.removeByAnd("WaKbChunk", UtilMisc.toMap("sourceId", src.getString("sourceId")));
        List<String[]> chunks = chunk(text);
        long seq = 0;
        for (String[] c : chunks) {
            delegator.create("WaKbChunk", UtilMisc.toMap("sourceId", src.getString("sourceId"), "seqNum", ++seq, "tenantId", tenantId,
                    "heading", c[0].length() > 250 ? c[0].substring(0, 250) : c[0], "content", c[1]));
        }
        if (UtilValidate.isNotEmpty(title) && UtilValidate.isEmpty(src.getString("title"))) {
            src.set("title", title.length() > 100 ? title.substring(0, 100) : title);
        }
        src.set("statusId", "READY");
        src.set("errorText", null);
        src.set("charCount", (long) text.length());
        src.set("chunkCount", seq);
        src.set("pageCount", (long) pages);
        src.set("syncedDate", UtilDateTime.nowTimestamp());
        src.store();
        invalidate(tenantId);
    }

    static void fail(GenericValue src, String why) {
        try {
            src.set("statusId", "ERROR");
            src.set("errorText", why);
            src.store();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
        }
    }

    /** Service waKbReadUrl: read a web source in the background. */
    public static Map<String, Object> readUrlService(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            GenericValue src = EntityQuery.use(delegator).from("WaKbSource").where("sourceId", context.get("sourceId")).queryOne();
            if (src == null) {
                return ServiceUtil.returnSuccess();
            }
            try {
                long pages = src.get("crawlPages") == null ? 1 : Math.max(1, Math.min(src.getLong("crawlPages"), WaUtil.propInt("ai.kb.max.pages", 30)));
                Object[] site = readSite(src.getString("url"), (int) pages);
                store(delegator, src, (String) site[0], (String) site[1], (Integer) site[2]);
            } catch (KbException e) {
                fail(src, e.getMessage());
            }
        } catch (Exception e) {
            Debug.logError(e, "Reading web source failed", MODULE);
        }
        return ServiceUtil.returnSuccess();
    }

    // ================================================================== search
    private static final class Doc {
        String sourceTitle;
        String heading;
        String content;
        Map<String, Integer> tf;
        int len;
    }

    private static final class Index {
        final List<Doc> docs = new ArrayList<>();
        final Map<String, Integer> df = new HashMap<>();
        long totalChars;
        double avgLen;
        long builtAt;
    }

    private static final Map<String, Index> CACHE = new ConcurrentHashMap<>();

    static void invalidate(String tenantId) {
        CACHE.remove(tenantId);
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");
    private static final Set<String> STOP = Set.of("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "be",
            "it", "you", "your", "we", "our", "i", "me", "my", "do", "does", "can", "what", "how", "when", "where", "which", "who", "with",
            "at", "by", "this", "that", "from", "as", "please", "pls", "hi", "hello", "hey", "ka", "ki", "ke", "hai", "hain", "kya", "mein",
            "का", "की", "के", "है", "हैं", "क्या", "में", "और", "को", "से", "पर", "यह", "कि");

    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            if (w.length() < 2 || STOP.contains(w)) {
                continue;
            }
            if (w.length() > 4 && w.endsWith("es") && !w.endsWith("ses")) {
                w = w.substring(0, w.length() - 2);
            } else if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) {
                w = w.substring(0, w.length() - 1);
            }
            out.add(w);
        }
        return out;
    }

    private static Index index(Delegator delegator, String tenantId) throws GenericEntityException {
        Index cached = CACHE.get(tenantId);
        if (cached != null && System.currentTimeMillis() - cached.builtAt < 10 * 60_000L) {
            return cached;
        }
        Index idx = new Index();
        Map<String, String> titles = new HashMap<>();
        for (GenericValue s : EntityQuery.use(delegator).from("WaKbSource").where("tenantId", tenantId, "statusId", "READY").queryList()) {
            titles.put(s.getString("sourceId"), s.getString("title"));
        }
        long lenSum = 0;
        for (GenericValue c : EntityQuery.use(delegator).from("WaKbChunk").where("tenantId", tenantId).orderBy("sourceId", "seqNum").queryList()) {
            if (!titles.containsKey(c.getString("sourceId"))) {
                continue;
            }
            Doc d = new Doc();
            d.sourceTitle = titles.get(c.getString("sourceId"));
            d.heading = c.getString("heading");
            d.content = c.getString("content");
            d.tf = new HashMap<>();
            List<String> toks = tokens((d.heading == null ? "" : d.heading + " ") + d.content + " " + (d.sourceTitle == null ? "" : d.sourceTitle));
            for (String t : toks) {
                d.tf.merge(t, 1, Integer::sum);
            }
            d.len = toks.size();
            lenSum += d.len;
            for (String t : d.tf.keySet()) {
                idx.df.merge(t, 1, Integer::sum);
            }
            idx.totalChars += d.content.length();
            idx.docs.add(d);
        }
        idx.avgLen = idx.docs.isEmpty() ? 1 : (double) lenSum / idx.docs.size();
        idx.builtAt = System.currentTimeMillis();
        CACHE.put(tenantId, idx);
        return idx;
    }

    /** What the agent may read for this question: everything when the knowledge is small, else the best matching pieces. */
    public static final class Context {
        public String text = "";
        public List<String> sources = new ArrayList<>();
        public int pieces;
        public boolean empty = true;
    }

    public static Context contextFor(Delegator delegator, String tenantId, String question) throws GenericEntityException {
        Index idx = index(delegator, tenantId);
        Context ctx = new Context();
        if (idx.docs.isEmpty()) {
            return ctx;
        }
        ctx.empty = false;
        int budget = WaUtil.propInt("ai.agent.context.chars", 14000);
        List<Doc> picked = new ArrayList<>();
        if (idx.totalChars <= WaUtil.propInt("ai.agent.full.context.chars", 20000)) {
            picked.addAll(idx.docs);
        } else {
            // BM25 ranking
            List<String> q = tokens(question);
            double k1 = 1.4;
            double b = 0.75;
            int n = idx.docs.size();
            List<double[]> scored = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Doc d = idx.docs.get(i);
                double s = 0;
                for (String t : new LinkedHashSet<>(q)) {
                    Integer f = d.tf.get(t);
                    if (f == null) {
                        continue;
                    }
                    double idf = Math.log(1 + (n - idx.df.get(t) + 0.5) / (idx.df.get(t) + 0.5));
                    s += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * d.len / idx.avgLen));
                }
                if (s > 0) {
                    scored.add(new double[] {s, i});
                }
            }
            scored.sort((x, y) -> Double.compare(y[0], x[0]));
            int used = 0;
            for (double[] sc : scored) {
                Doc d = idx.docs.get((int) sc[1]);
                if (used + d.content.length() > budget && !picked.isEmpty()) {
                    break;
                }
                picked.add(d);
                used += d.content.length();
            }
        }
        StringBuilder sb = new StringBuilder();
        Set<String> src = new LinkedHashSet<>();
        for (Doc d : picked) {
            sb.append("\n--- ").append(d.sourceTitle == null ? "Source" : d.sourceTitle);
            if (UtilValidate.isNotEmpty(d.heading)) {
                sb.append(" / ").append(d.heading);
            }
            sb.append(" ---\n").append(d.content).append('\n');
            src.add(d.sourceTitle == null ? "Source" : d.sourceTitle);
        }
        ctx.text = sb.toString();
        ctx.sources.addAll(src);
        ctx.pieces = picked.size();
        return ctx;
    }

    /** Summary for the settings page. */
    public static Map<String, Object> summary(Delegator delegator, String tenantId) throws GenericEntityException {
        long chars = 0;
        long ready = 0;
        for (GenericValue s : EntityQuery.use(delegator).from("WaKbSource").where("tenantId", tenantId).queryList()) {
            if ("READY".equals(s.getString("statusId"))) {
                ready++;
                chars += s.get("charCount") == null ? 0 : s.getLong("charCount");
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ready", ready);
        m.put("chars", chars);
        m.put("maxChars", (long) maxChars());
        m.put("pct", Math.min(100, Math.round(100.0 * chars / Math.max(1, maxChars()))));
        return m;
    }

    static Timestamp now() {
        return UtilDateTime.nowTimestamp();
    }
}
