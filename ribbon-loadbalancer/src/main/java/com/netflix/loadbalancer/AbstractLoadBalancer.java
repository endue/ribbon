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
package com.netflix.loadbalancer;

import java.util.List;

/**
 * AbstractLoadBalancer contains features required for most loadbalancing
 * implementations.
 * 
 * An anatomy of a typical LoadBalancer consists of 1. A List of Servers (nodes)
 * that are potentially bucketed based on a specific criteria. 2. A Class that
 * defines and implements a LoadBalacing Strategy via <code>IRule</code> 3. A
 * Class that defines and implements a mechanism to determine the
 * suitability/availability of the nodes/servers in the List.
 *
 * 一个LoadBalancer包含三个部分:
 * 1.服务列表(根据具体的一些标准被分开)
 * 2.通过IRule策略实现LoadBalacing
 * 3.定义一种机制来确定类别中服务的可用性
 *
 * @author stonse
 * 
 */
public abstract class AbstractLoadBalancer implements ILoadBalancer {

    /**
     * 定义三个服务组
     */
    public enum ServerGroup{
        ALL,
        STATUS_UP,
        STATUS_NOT_UP        
    }
        
    /**
     * delegate to {@link #chooseServer(Object)} with parameter null.
     *
     * 增加一个chooseServer()方法没有任何参数，然后调用父类的chooseServer()方法，参数为null
     *
     */
    public Server chooseServer() {
    	return chooseServer(null);
    }

    
    /**
     * List of servers that this Loadbalancer knows about
     *
     * 抽象方法，根据服务组获取服务列表
     *
     * @param serverGroup Servers grouped by status, e.g., {@link ServerGroup#STATUS_UP}
     */
    public abstract List<Server> getServerList(ServerGroup serverGroup);
    
    /**
     * Obtain LoadBalancer related Statistics
     *
     *抽象方法，获取与负载平衡器相关的统计信息
     *
     */
    public abstract LoadBalancerStats getLoadBalancerStats();    
}
