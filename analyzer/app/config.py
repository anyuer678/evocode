"""集中配置（pydantic-settings，读 .env / 环境变量）。"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """分析服务配置。LLM 相关留空即可跑（报告走规则版降级）。"""

    model_config = SettingsConfigDict(
        env_file=".env", env_file_encoding="utf-8", extra="ignore"
    )

    service_name: str = "evocode-analyzer"
    version: str = "0.1.0"

    data_dir: str = "data"
    status_dir: str = "data/status"

    llm_base_url: str = "http://127.0.0.1:11434/v1"
    llm_api_key: str = ""
    llm_model: str = "qwen2.5:7b"
    llm_timeout_seconds: float = 60.0
    llm_max_retries: int = 2
    # Embedding（P6 RAG）：复用 llm_base_url/llm_api_key 调 /embeddings；
    # 默认 bge-m3（1024 维）
    llm_embedding_model: str = "bge-m3"

    # RAG 知识块直连 PG（AD-P6-2）：只读写 knowledge_chunk 一张表；
    # 留空 = RAG 向量不可用（关键词兜底）
    pg_dsn: str = ""

    # Sonar（P3；留空/不可达 → 质量维度降级 N/A）
    sonar_host_url: str = "http://127.0.0.1:9000"
    sonar_token: str = ""
    sonar_scanner: str = ""  # 留空则 PATH 查找 sonar-scanner；找不到 → 不可用
    sonar_timeout_seconds: float = 30.0
    sonar_ce_poll_seconds: float = 3.0
    sonar_ce_poll_max: int = 40  # 最长等 120s


@lru_cache
def get_settings() -> Settings:
    return Settings()
