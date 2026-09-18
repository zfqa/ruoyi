package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeWebSearchClientTest
{
    @Test
    void parseBingKeepsTitleUrlAndSnippet()
    {
        String html = "<li class=\"b_algo\"><h2 class=\"\"><a target=\"_blank\" href=\"https://example.com/byd\">"
            + "<strong>BYD</strong> 2024 sales</a></h2><div class=\"b_caption\"><p class=\"b_lineclamp2\">"
            + "sold 4.27 million</p></div></li>"
            + "<h2><a href=\"https://www.bing.com/ck/a\">skip</a></h2><div class=\"b_caption\"><p>ad</p></div>";
        List<KnowledgeWebSearchClient.Hit> hits = KnowledgeWebSearchClient.parseBing(html);
        assertEquals(1, hits.size());
        assertEquals("BYD 2024 sales", hits.get(0).title());
        assertEquals("https://example.com/byd", hits.get(0).url());
        assertTrue(hits.get(0).snippet().contains("4.27 million"));
    }
}
