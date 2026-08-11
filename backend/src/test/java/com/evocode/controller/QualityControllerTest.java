package com.evocode.controller;

import com.evocode.common.BusinessException;
import com.evocode.dto.quality.QualityIssueResp;
import com.evocode.dto.quality.QualityIssuesResp;
import com.evocode.dto.quality.QualityMetricsResp;
import com.evocode.service.quality.QualityIssueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * T-U（P3c）：quality-issues 端点——分页参数校验、响应透传。
 */
class QualityControllerTest {

    private final QualityIssueService service = Mockito.mock(QualityIssueService.class);
    private QualityController controller;

    @BeforeEach
    void setUp() {
        controller = new QualityController(service);
    }

    @Test
    void rejectsBadPagination() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.query(1L, null, null, null, 0, 10));
        assertEquals(1003, e.getCode());
    }

    @Test
    void returnsQueryResult() {
        when(service.query(eq(1L), eq("MAJOR"), eq("BUG"), eq("OPEN"), anyInt(), anyInt()))
                .thenReturn(new QualityIssuesResp(
                        new QualityMetricsResp(2, 1, 0, null, null, null, true, null),
                        3,
                        List.of(new QualityIssueResp(1L, "MAJOR", "BUG", "s1", "a.py", 3,
                                "m", null, null, "PENDING", "OPEN"))));

        var data = controller.query(1L, "MAJOR", "BUG", "OPEN", 1, 10).getData();

        assertEquals(true, data.metrics().available());
        assertEquals(2, data.metrics().bugs());
        assertEquals(3, data.total());
        assertEquals("a.py", data.items().get(0).filePath());
    }
}
