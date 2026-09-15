from functools import lru_cache
from pathlib import Path
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    llm_api_key: str | None = Field(default=None, alias="LLM_API_KEY")
    llm_base_url: str = Field(default="", alias="LLM_BASE_URL")
    llm_model: str = Field(default="", alias="LLM_MODEL")
    llm_timeout: int = Field(default=60, alias="LLM_TIMEOUT")
    # Optional enterprise proxy.  It is intentionally opt-in: setting this
    # value makes only LLM requests use the approved proxy, while Excel parsing
    # and the local market-analysis API remain completely offline-capable.
    llm_proxy_url: str | None = Field(default=None, alias="LLM_PROXY_URL")
    storage_dir: str = Field(default="storage", alias="STORAGE_DIR")
    max_upload_mb: int = Field(default=50, alias="MAX_UPLOAD_MB")

    @property
    def upload_dir(self) -> Path:
        return Path(self.storage_dir) / "uploads"

    @property
    def processed_dir(self) -> Path:
        return Path(self.storage_dir) / "processed"

    @property
    def report_dir(self) -> Path:
        return Path(self.storage_dir) / "reports"

    @property
    def job_dir(self) -> Path:
        return Path(self.storage_dir) / "jobs"


@lru_cache
def get_settings() -> Settings:
    s = Settings()
    for p in [s.upload_dir, s.processed_dir, s.report_dir, s.job_dir]:
        p.mkdir(parents=True, exist_ok=True)
    return s
