package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.retrieval.domain.SectionLocator;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SectionLocatorRoundTripTest {
    @Test
    void preservesWordSectionForQaAndSolutionPersistence() {
        var locator = new SectionLocator(List.of("验收流程", "验收材料"), "p1");
        var json = new ObjectMapper();
        assertEquals("SECTION", QaLocatorJson.type(locator));
        assertEquals(locator, QaLocatorJson.read(json, "SECTION", QaLocatorJson.write(json, locator)));
        assertEquals(locator, SolutionLocatorJson.read(SolutionLocatorJson.write(locator)));
    }
}
