package com.evocode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 集中配置项（03 §2.8：禁止散落 @Value）。
 */
@Data
@ConfigurationProperties(prefix = "evocode")
public class EvocodeProperties {

    /** 代码库磁盘根目录（data/projects/{id}/） */
    private String dataDir = "data";

    /** analyzer 内部服务地址（仅 127.0.0.1） */
    private String analyzerUrl = "http://127.0.0.1:8081";

    /** LLM 超时/重试（chat 场景使用，随 P6 落地） */
    private int llmTimeoutSeconds = 60;
    private int llmMaxRetries = 2;

    /** zip 上传上限（FR-1.1：解压后 ≤500MB、文件数 ≤5 万） */
    private long uploadMaxExtractBytes = 500L * 1024 * 1024;
    private int uploadMaxFileCount = 50_000;

    /** Git 克隆（FR-1.2：默认 depth=1，超时 5 分钟，可配置代理） */
    private String gitExecutable = "git";
    private int gitCloneTimeoutSeconds = 300;
    private String gitProxy = "";

    /** 文件内容接口限制（06 §3.8：≤2MB） */
    private long contentMaxBytes = 2L * 1024 * 1024;
}
