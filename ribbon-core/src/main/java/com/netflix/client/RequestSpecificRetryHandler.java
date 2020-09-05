package com.netflix.client;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;

import javax.annotation.Nullable;
import java.net.SocketException;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of RetryHandler created for each request which allows for request
 * specific override
 * 看上面的翻译，大意就是为每个request创建一个RetryHandler
 */
public class RequestSpecificRetryHandler implements RetryHandler {

    // 重试策略
    private final RetryHandler fallback;
    // 同一服务允许重试次数，默认-1
    private int retrySameServer = -1;
    // 其他服务允许重试次数，默认-1
    private int retryNextServer = -1;
    // 是否所有连接异常都可以重试，SocketException相关
    private final boolean okToRetryOnConnectErrors;
    // 是否所有异常都可以重试
    private final boolean okToRetryOnAllErrors;
    
    protected List<Class<? extends Throwable>> connectionRelated = 
            Lists.<Class<? extends Throwable>>newArrayList(SocketException.class);

    /**
     * 构造方法，如果不传重试策略，默认DefaultLoadBalancerRetryHandler
     * @param okToRetryOnConnectErrors
     * @param okToRetryOnAllErrors
     */
    public RequestSpecificRetryHandler(boolean okToRetryOnConnectErrors, boolean okToRetryOnAllErrors) {
        this(okToRetryOnConnectErrors, okToRetryOnAllErrors, RetryHandler.DEFAULT, null);    
    }
    
    public RequestSpecificRetryHandler(boolean okToRetryOnConnectErrors, boolean okToRetryOnAllErrors, RetryHandler baseRetryHandler, @Nullable IClientConfig requestConfig) {
        Preconditions.checkNotNull(baseRetryHandler);
        this.okToRetryOnConnectErrors = okToRetryOnConnectErrors;
        this.okToRetryOnAllErrors = okToRetryOnAllErrors;
        this.fallback = baseRetryHandler;
        // 根据每个IClientConfig获取它们自己的配置
        if (requestConfig != null) {
            requestConfig.getIfSet(CommonClientConfigKey.MaxAutoRetries).ifPresent(
                    value -> retrySameServer = value
            );
            requestConfig.getIfSet(CommonClientConfigKey.MaxAutoRetriesNextServer).ifPresent(
                    value -> retryNextServer = value
            );
        }
    }
    
    public boolean isConnectionException(Throwable e) {
        return Utils.isPresentAsCause(e, connectionRelated);
    }

    @Override
    public boolean isRetriableException(Throwable e, boolean sameServer) {
        if (okToRetryOnAllErrors) {
            return true;
        } 
        else if (e instanceof ClientException) {
            // 如果发生客户端连接异常，这里会判断异常类型，然后返回是否允许在同一个服务重试
            ClientException ce = (ClientException) e;
            if (ce.getErrorType() == ClientException.ErrorType.SERVER_THROTTLED) {
                return !sameServer;
            } else {
                return false;
            }
        } 
        else  {
            return okToRetryOnConnectErrors && isConnectionException(e);
        }
    }

    @Override
    public boolean isCircuitTrippingException(Throwable e) {
        return fallback.isCircuitTrippingException(e);
    }

    @Override
    public int getMaxRetriesOnSameServer() {
        if (retrySameServer >= 0) {
            return retrySameServer;
        }
        return fallback.getMaxRetriesOnSameServer();
    }

    @Override
    public int getMaxRetriesOnNextServer() {
        if (retryNextServer >= 0) {
            return retryNextServer;
        }
        return fallback.getMaxRetriesOnNextServer();
    }    
}
