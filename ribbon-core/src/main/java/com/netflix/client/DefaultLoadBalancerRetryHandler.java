/*
 *
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.client;

import com.google.common.collect.Lists;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;

/**
 * A default {@link RetryHandler}. The implementation is limited to
 * known exceptions in java.net. Specific client implementation should provide its own
 * {@link RetryHandler}
 * 
 * @author awang
 */
public class DefaultLoadBalancerRetryHandler implements RetryHandler {

    // 保存可以重试的异常类
    @SuppressWarnings("unchecked")
    private List<Class<? extends Throwable>> retriable = 
            Lists.<Class<? extends Throwable>>newArrayList(ConnectException.class, SocketTimeoutException.class);
    // 熔断异常
    @SuppressWarnings("unchecked")
    private List<Class<? extends Throwable>> circuitRelated = 
            Lists.<Class<? extends Throwable>>newArrayList(SocketException.class, SocketTimeoutException.class);

    // 同一个服务可重试次数
    protected final int retrySameServer;
    // 可重试其他服务的次数
    protected final int retryNextServer;
    // 所有操作是否都可以重试
    protected final boolean retryEnabled;

    public DefaultLoadBalancerRetryHandler() {
        this.retrySameServer = 0;
        this.retryNextServer = 0;
        this.retryEnabled = false;
    }
    
    public DefaultLoadBalancerRetryHandler(int retrySameServer, int retryNextServer, boolean retryEnabled) {
        this.retrySameServer = retrySameServer;
        this.retryNextServer = retryNextServer;
        this.retryEnabled = retryEnabled;
    }
    
    public DefaultLoadBalancerRetryHandler(IClientConfig clientConfig) {
        // 读取配置文件中的值，默认 0
        this.retrySameServer = clientConfig.getOrDefault(CommonClientConfigKey.MaxAutoRetries);
        // 读取配置文件中的值，默认 1
        this.retryNextServer = clientConfig.getOrDefault(CommonClientConfigKey.MaxAutoRetriesNextServer);
        // 读取配置文件中的值，默认 false
        this.retryEnabled = clientConfig.getOrDefault(CommonClientConfigKey.OkToRetryOnAllOperations);
    }

    @Override
    public boolean isRetriableException(Throwable e, boolean sameServer) {
        if (retryEnabled) {// 开启所有请求都可以重试
            if (sameServer) {
                return Utils.isPresentAsCause(e, getRetriableExceptions());
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true if {@link SocketException} or {@link SocketTimeoutException} is a cause in the Throwable.
     */
    @Override
    public boolean isCircuitTrippingException(Throwable e) {
        return Utils.isPresentAsCause(e, getCircuitRelatedExceptions());        
    }

    @Override
    public int getMaxRetriesOnSameServer() {
        return retrySameServer;
    }

    @Override
    public int getMaxRetriesOnNextServer() {
        return retryNextServer;
    }

    protected List<Class<? extends Throwable>> getRetriableExceptions() {
        return retriable;
    }
    
    protected List<Class<? extends Throwable>>  getCircuitRelatedExceptions() {
        return circuitRelated;
    }
}
