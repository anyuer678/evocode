package com.evocode.service.analysis;

import com.evocode.common.BusinessException;
import com.evocode.config.EvocodeProperties;
import com.evocode.dto.scan.ScanResultResp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * AnalyzerClient：成功解析 + 不可达映射 3001（08 T-U-08 前置行为）。
 */
class AnalyzerClientTest {

    private MockRestServiceServer server;
    private AnalyzerClient client;

    @BeforeEach
    void setUp() {
        EvocodeProperties props = new EvocodeProperties();
        props.setAnalyzerUrl("http://127.0.0.1:18081");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AnalyzerClient(props, builder, new ObjectMapper());
    }

    @Test
    void scanSuccessParsed() {
        String body = """
                {"languages":{"Java":61.2},"locTotal":20431,"fileCount":412,"ignoredCount":88,
                 "frameworks":["Spring Boot"],"hasBackend":true,"hasFrontend":true,"dbHint":["MySQL"],
                 "files":[{"path":"src/A.java","language":"Java","loc":180,"sizeBytes":4210}],
                 "skippedBigFiles":1}""";
        server.expect(requestTo("http://127.0.0.1:18081/analyze/v1/scan"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        ScanResultResp result = client.scan(1L, "data/projects/1");

        assertEquals(20431L, result.locTotal());
        assertEquals(412, result.fileCount());
        assertEquals(1, result.files().size());
        assertEquals("Java", result.files().get(0).language());
        server.verify();
    }

    @Test
    void analyzerServerErrorMappedTo3001() {
        server.expect(requestTo("http://127.0.0.1:18081/analyze/v1/scan"))
                .andRespond(withServerError());
        BusinessException e = assertThrows(BusinessException.class,
                () -> client.scan(1L, "data/projects/1"));
        assertEquals(3001, e.getCode());
    }
}
