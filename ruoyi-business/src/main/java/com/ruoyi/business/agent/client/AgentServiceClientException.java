package com.ruoyi.business.agent.client;

/** 对 Python agent-service 的安全错误分类，不携带上游响应正文。 */
public class AgentServiceClientException extends Exception
{
    private final String errorCode;
    private final Integer upstreamStatus;

    public AgentServiceClientException(String errorCode, String message)
    {
        this(errorCode, message, null, null);
    }

    public AgentServiceClientException(String errorCode, String message, Throwable cause)
    {
        this(errorCode, message, null, cause);
    }

    public AgentServiceClientException(String errorCode, String message, Integer upstreamStatus, Throwable cause)
    {
        super(message, cause);
        this.errorCode = errorCode;
        this.upstreamStatus = upstreamStatus;
    }

    public String getErrorCode()
    {
        return errorCode;
    }

    public Integer getUpstreamStatus()
    {
        return upstreamStatus;
    }
}


