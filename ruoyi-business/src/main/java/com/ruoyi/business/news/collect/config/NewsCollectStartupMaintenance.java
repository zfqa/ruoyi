package com.ruoyi.business.news.collect.config;

import com.ruoyi.business.news.collect.mapper.NewsCollectMapper;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments; import org.springframework.boot.ApplicationRunner; import org.springframework.stereotype.Component;

/** Marks in-process work that cannot survive a Java restart as retryable failure. */
@Component
public class NewsCollectStartupMaintenance implements ApplicationRunner {
 private static final Logger LOG=LoggerFactory.getLogger(NewsCollectStartupMaintenance.class); private final NewsCollectMapper mapper;
 public NewsCollectStartupMaintenance(NewsCollectMapper mapper){this.mapper=mapper;}
 @Override public void run(ApplicationArguments args) { try { int count=mapper.markInterruptedRunningTasks(); if(count>0) LOG.warn("Reconciled {} interrupted news collect task(s)",count); } catch(Exception e){LOG.error("News collect startup maintenance failed; startup continues",e);} }
}


