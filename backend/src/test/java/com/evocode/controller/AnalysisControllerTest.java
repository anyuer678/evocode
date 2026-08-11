package com.evocode.controller;

import com.evocode.common.BusinessException;
import com.evocode.dto.analysis.AnalysisCreateReq;
import com.evocode.dto.analysis.AnalysisResp;
import com.evocode.dto.analysis.AnalysisStatusResp;
import com.evocode.dto.analysis.ReportDetailResp;
import com.evocode.enums.AnalysisStatus;
import com.evocode.enums.Stage;
import com.evocode.service.analysis.AnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * T-U（P2a）：发起分析 202、轮询 200、参数与业务错误码。
 */
class AnalysisControllerTest {

    private final AnalysisService service = Mockito.mock(AnalysisService.class);
    private AnalysisController controller;

    @BeforeEach
    void setUp() {
        controller = new AnalysisController(service);
    }

    @Test
    void createReturns202() {
        when(service.create(1L, "FULL")).thenReturn(new AnalysisResp(
                10L, 1L, "FULL", AnalysisStatus.PENDING.name(), 0,
                Stage.QUEUED.name(), OffsetDateTime.now()));

        var resp = controller.create(1L, new AnalysisCreateReq("FULL"));
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        assertEquals(10L, resp.getBody().getData().id());
    }

    @Test
    void createPropagatesBusyError() {
        when(service.create(1L, "FULL")).thenThrow(new BusinessException(
                com.evocode.common.ErrorCode.ANALYSIS_BUSY, null));
        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.create(1L, new AnalysisCreateReq("FULL")));
        assertEquals(2002, e.getCode());
    }

    @Test
    void historyRejectsBadPagination() {
        BusinessException e = assertThrows(BusinessException.class, () -> controller.history(1L, 0, 10));
        assertEquals(1003, e.getCode());
    }

    @Test
    void statusReturnsPollingPayload() {
        when(service.status(10L)).thenReturn(new AnalysisStatusResp(
                10L, AnalysisStatus.RUNNING.name(), 45, Stage.SCAN.name(), null));
        var data = controller.status(10L).getData();
        assertEquals(45, data.progress());
        assertEquals(Stage.SCAN.name(), data.stage());
    }

    @Test
    void reportReturnsPayload() {
        when(service.report(10L)).thenReturn(new ReportDetailResp(
                10L, OffsetDateTime.now(), "RULES", "report-1.0", Map.of("healthScore", 82)));
        var data = controller.report(10L).getData();
        assertEquals("RULES", data.source());
        assertEquals("report-1.0", data.promptVersion());
        assertEquals(82, data.report().get("healthScore"));
    }

    @Test
    void regenerateReturns202() {
        when(service.regenerate(10L)).thenReturn(new AnalysisStatusResp(
                10L, AnalysisStatus.RUNNING.name(), 75, Stage.REPORT.name(), null));
        var resp = controller.regenerate(10L);
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        assertEquals(75, resp.getBody().getData().progress());
    }

    @Test
    void createAcceptsNullBodyAsUnsupportedType() {
        // 缺省 body → type=null → 走 service 校验（1002）
        when(service.create(anyLong(), Mockito.isNull())).thenThrow(new BusinessException(
                com.evocode.common.ErrorCode.PARAM_INVALID, "v0.1 仅支持 FULL 分析"));
        BusinessException e = assertThrows(BusinessException.class, () -> controller.create(1L, null));
        assertEquals(1002, e.getCode());
    }
}
