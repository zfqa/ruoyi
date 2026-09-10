package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ruoyi.business.knowledge.domain.KnowledgeBase;
import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;
import com.ruoyi.business.knowledge.service.impl.KnowledgeBaseServiceImpl;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeBaseServicePolicyTest
{
    private KnowledgeBaseMapper mapper;
    private KnowledgeBaseServiceImpl service;

    @BeforeEach
    void setUp() throws Exception
    {
        mapper = mock(KnowledgeBaseMapper.class);
        service = new KnowledgeBaseServiceImpl();
        Field field = KnowledgeBaseServiceImpl.class.getDeclaredField("knowledgeBaseMapper");
        field.setAccessible(true);
        field.set(service, mapper);
    }

    @Test
    void normalizesFixedSourcePolicyFields()
    {
        when(mapper.insertKnowledgeBase(any())).thenReturn(1);
        KnowledgeBase source = source();
        source.setAllowedRoleIds(" 2,3,2 ");

        assertEquals(1, service.insertKnowledgeBase(source));
        assertEquals("PDF", source.getSourceType());
        assertEquals("2,3", source.getAllowedRoleIds());
        verify(mapper).insertKnowledgeBase(source);
    }

    @Test
    void rejectsInvalidRoleList()
    {
        KnowledgeBase source = source();
        source.setAllowedRoleIds("2,admin");
        assertThrows(IllegalArgumentException.class, () -> service.insertKnowledgeBase(source));
    }

    @Test
    void preventsChangingTypeOrCurrentVersionAfterIngest()
    {
        KnowledgeBase existing = source();
        existing.setId(9L); existing.setCurrentVersionId(88L); existing.setStatus("2");
        when(mapper.selectKnowledgeBaseById(9L)).thenReturn(existing);
        KnowledgeBase edited = source();
        edited.setId(9L); edited.setSourceType("REPORT"); edited.setCurrentVersionId(999L);

        assertThrows(IllegalArgumentException.class, () -> service.updateKnowledgeBase(edited));
    }

    private KnowledgeBase source()
    {
        KnowledgeBase source = new KnowledgeBase();
        source.setSourceCode("POC-PDF-001"); source.setSourceName("固定测试报告"); source.setSourceType("pdf");
        source.setConfidentiality("internal"); source.setAllowedPurpose("仅限POC问答测试");
        source.setAllowedRoleIds(""); source.setEnabled("1"); source.setStatus("0");
        return source;
    }
}
