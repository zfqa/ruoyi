package com.ruoyi.business.knowledge.service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 固定知识库没有命中时，用公开网页搜索补齐回答材料。 */
public class KnowledgeWebSearchClient
{
    public record Hit(String title, String url, String snippet) { }

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Pattern BING_RESULT = Pattern.compile(
        "(?s)<h2[^>]*>\\s*<a\\b[^>]*?href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>\\s*</h2>\\s*"
            + "<div class=\"b_caption\"[^>]*>\\s*<p[^>]*>(.*?)</p>");
    private static final Pattern DDG_RESULT = Pattern.compile(
        "(?s)<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>");
    private static final Pattern DDG_SNIPPET = Pattern.compile(
        "(?s)<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>");
    private static final int LIMIT = 5;

    private final HttpClient httpClient;

    public KnowledgeWebSearchClient(HttpClient httpClient)
    {
        this.httpClient = httpClient;
    }

    public List<Hit> search(String query)
    {
        String actual = query == null ? "" : query.trim();
        if (actual.length() < 2) return List.of();
        List<Hit> hits = List.of();
        try
        {
            hits = parseBing(fetch("https://cn.bing.com/search?setlang=zh-CN&ensearch=0&q="
                + URLEncoder.encode(actual, StandardCharsets.UTF_8)));
        }
        catch (Exception ignored)
        {
            hits = List.of();
        }
        if (!hits.isEmpty()) return hits;
        try
        {
            return parseDuckDuckGo(fetch("https://html.duckduckgo.com/html/?q="
                + URLEncoder.encode(actual, StandardCharsets.UTF_8)));
        }
        catch (Exception ignored)
        {
            return List.of();
        }
    }

    static List<Hit> parseBing(String html)
    {
        List<Hit> hits = new ArrayList<>();
        if (html == null || html.isBlank()) return hits;
        Matcher matcher = BING_RESULT.matcher(html);
        while (matcher.find() && hits.size() < LIMIT)
        {
            String url = normalizeUrl(matcher.group(1));
            if (url.contains("://www.bing.com/") || url.contains("://cn.bing.com/")) continue;
            add(hits, cleanText(matcher.group(2)), url, cleanText(matcher.group(3)));
        }
        return hits;
    }

    static List<Hit> parseDuckDuckGo(String html)
    {
        List<Hit> hits = new ArrayList<>();
        if (html == null || html.isBlank()) return hits;
        Matcher snippets = DDG_SNIPPET.matcher(html);
        List<String> snippetList = new ArrayList<>();
        while (snippets.find()) snippetList.add(cleanText(snippets.group(1)));
        Matcher matcher = DDG_RESULT.matcher(html);
        int index = 0;
        while (matcher.find() && hits.size() < LIMIT)
        {
            String snippet = index < snippetList.size() ? snippetList.get(index) : "";
            index++;
            add(hits, cleanText(matcher.group(2)), normalizeUrl(matcher.group(1)), snippet);
        }
        return hits;
    }

    private static void add(List<Hit> hits, String title, String url, String snippet)
    {
        if (title.isBlank() || !url.startsWith("http")) return;
        for (Hit existing : hits)
        {
            if (existing.url().equals(url)) return;
        }
        hits.add(new Hit(title, url, snippet));
    }

    private String fetch(String url) throws Exception
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Accept", "text/html,application/xhtml+xml")
            .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) return "";
        return response.body();
    }

    private static String normalizeUrl(String raw)
    {
        String url = raw == null ? "" : raw.replace("&amp;", "&").trim();
        int marker = url.indexOf("uddg=");
        if (marker >= 0)
        {
            String encoded = url.substring(marker + 5);
            int amp = encoded.indexOf('&');
            if (amp >= 0) encoded = encoded.substring(0, amp);
            try
            {
                url = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            }
            catch (IllegalArgumentException ignored)
            {
                return url;
            }
        }
        return url;
    }

    private static String cleanText(String html)
    {
        if (html == null) return "";
        String text = html.replaceAll("(?s)<[^>]+>", " ");
        Map<String, String> entities = new LinkedHashMap<>();
        entities.put("&amp;", "&");
        entities.put("&lt;", "<");
        entities.put("&gt;", ">");
        entities.put("&quot;", "\"");
        entities.put("&#39;", "'");
        entities.put("&nbsp;", " ");
        for (Map.Entry<String, String> entry : entities.entrySet())
            text = text.replace(entry.getKey(), entry.getValue());
        return text.replaceAll("\\s+", " ").trim();
    }
}
