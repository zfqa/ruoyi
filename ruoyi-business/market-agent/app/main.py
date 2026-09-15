from fastapi import FastAPI, Request
from contextlib import asynccontextmanager
from app.api.routes import router, resume_upload_jobs
from app.core.config import get_settings
from app.core.llm import configure_runtime_llm
from app.version import APP_VERSION

get_settings()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    resume_upload_jobs()
    yield

app = FastAPI(title="若依整车市场洞察服务", version=APP_VERSION, lifespan=lifespan)


@app.middleware("http")
async def receive_ruoyi_llm_configuration(request: Request, call_next):
    """Java网关每次请求下发统一配置，支持后台任务并避免第二份.env密钥。"""
    managed = request.headers.get("X-Market-LLM-Managed")
    if managed is not None:
        configure_runtime_llm(
            request.headers.get("X-Market-LLM-Api-Url", ""),
            request.headers.get("X-Market-LLM-Model", ""),
            request.headers.get("X-Market-LLM-Api-Key", ""),
            managed.lower() == "true",
        )
    return await call_next(request)


app.include_router(router, prefix="/api")
