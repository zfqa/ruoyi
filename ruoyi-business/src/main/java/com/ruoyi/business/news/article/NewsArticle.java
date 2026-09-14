package com.ruoyi.business.news.article;

/** MySQL新闻业务记录；正式检索内容同时发布到统一知识库。 */
public class NewsArticle
{
    private Long id;
    private Long crawlTaskId;
    private String sourceName;
    private String sourceSite;
    private String title;
    private String content;
    private String url;
    private String originalUrl;
    private String canonicalUrl;
    private String publishedAt;
    private String crawledAt;
    private String matchedKeywords;
    private String contentHash;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getCrawlTaskId(){return crawlTaskId;} public void setCrawlTaskId(Long v){crawlTaskId=v;}
    public String getSourceName(){return sourceName;} public void setSourceName(String v){sourceName=v;}
    public String getSourceSite(){return sourceSite;} public void setSourceSite(String v){sourceSite=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;}
    public String getContent(){return content;} public void setContent(String v){content=v;}
    public String getUrl(){return url;} public void setUrl(String v){url=v;}
    public String getOriginalUrl(){return originalUrl;} public void setOriginalUrl(String v){originalUrl=v;}
    public String getCanonicalUrl(){return canonicalUrl;} public void setCanonicalUrl(String v){canonicalUrl=v;}
    public String getPublishedAt(){return publishedAt;} public void setPublishedAt(String v){publishedAt=v;}
    public String getCrawledAt(){return crawledAt;} public void setCrawledAt(String v){crawledAt=v;}
    public String getMatchedKeywords(){return matchedKeywords;} public void setMatchedKeywords(String v){matchedKeywords=v;}
    public String getContentHash(){return contentHash;} public void setContentHash(String v){contentHash=v;}
}
