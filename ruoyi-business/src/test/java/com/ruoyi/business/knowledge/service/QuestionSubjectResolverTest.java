package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuestionSubjectResolverTest
{
    @Test
    void parsesLlmSubjectsAndIgnoresNoise()
    {
        String raw = "```json\n{\"subjects\":[\"奇瑞\",\"Chery\",\"销量\"],\"must_match\":true}\n```";
        QuestionSubjectResolver.SubjectPlan plan = QuestionSubjectResolver.parseLlmJson(raw);
        assertTrue(plan.subjects().contains("奇瑞"));
        assertTrue(plan.subjects().contains("Chery"));
        assertFalse(plan.subjects().contains("销量"));
        assertTrue(plan.mustMatch());
    }

    @Test
    void emptySubjectsDoNotForceMatch()
    {
        QuestionSubjectResolver.SubjectPlan plan = QuestionSubjectResolver.parseLlmJson(
            "{\"subjects\":[],\"must_match\":true}");
        assertFalse(plan.mustMatch());
        assertFalse(plan.hasSubjects());
    }

    @Test
    void mergesRuleAndLlmSubjects()
    {
        QuestionSubjectResolver.SubjectPlan merged = QuestionSubjectResolver.merge(
            QuestionSubjectResolver.fromRules("比亚迪和奇瑞谁的销量更高"),
            QuestionSubjectResolver.parseLlmJson("{\"subjects\":[\"奇瑞\",\"比亚迪\"],\"must_match\":true}"));
        assertTrue(merged.subjects().contains("比亚迪"));
        assertTrue(merged.subjects().contains("奇瑞"));
        assertEquals("RULE+LLM", merged.source());
        assertTrue(merged.mustMatch());
    }

    @Test
    void enrichesQueriesWithSubjectWithoutDuplicating()
    {
        List<String> queries = QuestionSubjectResolver.enrichSearchQueries(
            List.of("2025年销量", "奇瑞出口"), List.of("奇瑞"));
        assertTrue(queries.contains("奇瑞"));
        assertTrue(queries.contains("奇瑞 2025年销量"));
        assertTrue(queries.contains("奇瑞出口"));
        assertFalse(queries.contains("奇瑞 奇瑞出口"));
    }

    @Test
    void keepsOnlyAllowedSourceIds()
    {
        List<Long> keep = QuestionSubjectResolver.parseKeepSourceIds(
            "{\"keep_source_ids\":[131,999]}", Set.of(130L, 131L));
        assertEquals(List.of(131L), keep);
    }
}
