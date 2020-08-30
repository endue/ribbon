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

import com.google.common.annotations.VisibleForTesting;
import com.netflix.client.ClientFactory;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A LoadBalancer that has the capabilities to obtain the candidate list of
 * servers using a dynamic source. i.e. The list of servers can potentially be
 * changed at Runtime. It also contains facilities wherein the list of servers
 * can be passed through a Filter criteria to filter out servers that do not
 * meet the desired criteria.
 *
 * BaseLoadBalancer只提供给了服务列表的添加、获取功能
 * DynamicServerListLoadBalancer是在BaseLoadBalancer上扩展了如下功能：
 *  1.动态更新服务列表，依赖于ServerList
 *  2.对服务列表的过滤，依赖于ServerListFilter
 * 
 * @author stonse
 * 
 */
public class DynamicServerListLoadBalancer<T extends Server> extends BaseLoadBalancer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicServerListLoadBalancer.class);
    // 这俩属性在这里没用到
    boolean isSecure = false;
    boolean useTunnel = false;

    // 标识ServerList正在更新服务列表
    protected AtomicBoolean serverListUpdateInProgress = new AtomicBoolean(false);
    // ServerList 泛型为T，T继承了Server，其只有一个继承者为DiscoveryEnabledServer
    // DynamicServerListLoadBalancer的动态更新功能就是基于ServerList，其有两个实现类
    // com.netflix.loadbalancer.ConfigurationBasedServerList 读取配置文件中的listOfServers的值
    // com.netflix.niws.loadbalancer.DiscoveryEnabledNIWSServerList 基于EurekaClient读取所有服务列表
    volatile ServerList<T> serverListImpl;
    // 过滤器
    volatile ServerListFilter<T> filter;

    protected final ServerListUpdater.UpdateAction updateAction = new ServerListUpdater.UpdateAction() {
        @Override
        public void doUpdate() {
            // 执行更新服务列表的方法
            updateListOfServers();
        }
    };
    // 动态更新服务列表
    // ServerListUpdater有两个实现类
    // com.netflix.loadbalancer.PollingServerListUpdater 定时任务，延迟1s执行之后每30s执行一次
    // com.netflix.niws.loadbalancer.EurekaNotificationServerListUpdater 基于CacheRefreshedEvent事件更新
    protected volatile ServerListUpdater serverListUpdater;

    public DynamicServerListLoadBalancer() {
        super();
    }

    @Deprecated
    public DynamicServerListLoadBalancer(IClientConfig clientConfig, IRule rule, IPing ping, 
            ServerList<T> serverList, ServerListFilter<T> filter) {
        this(
                clientConfig,
                rule,
                ping,
                serverList,
                filter,
                new PollingServerListUpdater()
        );
    }

    public DynamicServerListLoadBalancer(IClientConfig clientConfig, IRule rule, IPing ping,
                                         ServerList<T> serverList, ServerListFilter<T> filter,
                                         ServerListUpdater serverListUpdater) {
        // 调用服务构造方法初始化父类BaseLoadBalancer
        super(clientConfig, rule, ping);
        this.serverListImpl = serverList;
        this.filter = filter;
        this.serverListUpdater = serverListUpdater;
        if (filter instanceof AbstractServerListFilter) {
            ((AbstractServerListFilter) filter).setLoadBalancerStats(getLoadBalancerStats());
        }
        // 初始化
        restOfInit(clientConfig);
    }

    public DynamicServerListLoadBalancer(IClientConfig clientConfig) {
        initWithNiwsConfig(clientConfig);
    }
    
    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        this.initWithNiwsConfig(clientConfig, ClientFactory::instantiateInstanceWithClientConfig);

    }

    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig, Factory factory) {
        try {
            super.initWithNiwsConfig(clientConfig, factory);
            String niwsServerListClassName = clientConfig.getOrDefault(CommonClientConfigKey.NIWSServerListClassName);

            ServerList<T> niwsServerListImpl = (ServerList<T>) factory.create(niwsServerListClassName, clientConfig);
            this.serverListImpl = niwsServerListImpl;

            if (niwsServerListImpl instanceof AbstractServerList) {
                AbstractServerListFilter<T> niwsFilter = ((AbstractServerList) niwsServerListImpl)
                        .getFilterImpl(clientConfig);
                niwsFilter.setLoadBalancerStats(getLoadBalancerStats());
                this.filter = niwsFilter;
            }

            String serverListUpdaterClassName = clientConfig.getOrDefault(
                    CommonClientConfigKey.ServerListUpdaterClassName);

            this.serverListUpdater = (ServerListUpdater) factory.create(serverListUpdaterClassName, clientConfig);

            restOfInit(clientConfig);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Exception while initializing NIWSDiscoveryLoadBalancer:"
                            + clientConfig.getClientName()
                            + ", niwsClientConfig:" + clientConfig, e);
        }
    }

    void restOfInit(IClientConfig clientConfig) {
        // 调用父类enablePrimingConnections属性，默认false
        boolean primeConnection = this.isEnablePrimingConnections();
        // turn this off to avoid duplicated asynchronous priming done in BaseLoadBalancer.setServerList()
        // 关闭此选项以避免在BaseLoadBalancer.setServerList()中重复进行异步启动。
        this.setEnablePrimingConnections(false);
        // 调用serverListUpdater的start()方法，传入初始化的updateAction动态更新服务列表
        enableAndInitLearnNewServersFeature();
        // 更新服务列表(updateAction也是里面也是调用的这个方法)
        updateListOfServers();
        // 如果父类的primeConnection不为null且enablePrimingConnections设置为true，
        // 在更新完自己的服务列表后，需要调用父类primeConnection的primeConnections方法，去检查服务
        if (primeConnection && this.getPrimeConnections() != null) {
            this.getPrimeConnections()
                    .primeConnections(getReachableServers());
        }
        // 还原父类enablePrimingConnections属性
        this.setEnablePrimingConnections(primeConnection);
        LOGGER.info("DynamicServerListLoadBalancer for client {} initialized: {}", clientConfig.getClientName(), this.toString());
    }


    /**
     * 设置服务列表，覆盖了父类com.netflix.loadbalancer.BaseLoadBalancer#setServersList(java.util.List)的方法
     * @param lsrv
     */
    @Override
    public void setServersList(List lsrv) {
        // 调用父类的方法
        super.setServersList(lsrv);
        // 遍历先添加的服务列表
        List<T> serverList = (List<T>) lsrv;
        Map<String, List<Server>> serversInZones = new HashMap<String, List<Server>>();
        for (Server server : serverList) {
            // make sure ServerStats is created to avoid creating them on hot
            // path
            // 第一步：获取父类属性LoadBalancer(统计信息)
            // 第二步：获取LoadBalancer的属性LoadingCache<Server, ServerStats> serverStatsCache，是一个缓存有效期默认30分钟，然后将服务和对应的ServerStats缓存起来
            getLoadBalancerStats().getSingleServerStat(server);
            // 获取服务的zone
            String zone = server.getZone();
            if (zone != null) {
                zone = zone.toLowerCase();
                // 将传入的参数lsrv，根据zone进行分组记录到HashMap serversInZones中
                List<Server> servers = serversInZones.get(zone);
                if (servers == null) {
                    servers = new ArrayList<Server>();
                    serversInZones.put(zone, servers);
                }
                servers.add(server);
            }
        }
        // 更新父类的zoneStatsMap属性,也就是一个个zone的ZoneStats
        setServerListForZones(serversInZones);
    }

    /**
     * 参数zoneServersMap，key 是 zone、value 是 serverList
     * 更新父类的zoneStatsMap属性,也就是一个个zone的ZoneStats
     * @param zoneServersMap
     */
    protected void setServerListForZones(
            Map<String, List<Server>> zoneServersMap) {
        LOGGER.debug("Setting server list for zones: {}", zoneServersMap);
        // 第一步：获取父类属性LoadBalancer(统计信息)
        // 第二步：同步到LoadBalancer的属性Map<String, List<? extends Server>> upServerListZoneMap中
        getLoadBalancerStats().updateZoneServerMapping(zoneServersMap);
    }

    public ServerList<T> getServerListImpl() {
        return serverListImpl;
    }

    public void setServerListImpl(ServerList<T> niwsServerList) {
        this.serverListImpl = niwsServerList;
    }

    public ServerListFilter<T> getFilter() {
        return filter;
    }

    public void setFilter(ServerListFilter<T> filter) {
        this.filter = filter;
    }

    public ServerListUpdater getServerListUpdater() {
        return serverListUpdater;
    }

    public void setServerListUpdater(ServerListUpdater serverListUpdater) {
        this.serverListUpdater = serverListUpdater;
    }

    @Override
    /**
     * Makes no sense to ping an inmemory disc client
     * 
     */
    public void forceQuickPing() {
        // no-op
    }

    /**
     * Feature that lets us add new instances (from AMIs) to the list of
     * existing servers that the LB will use Call this method if you want this
     * feature enabled
     */
    public void enableAndInitLearnNewServersFeature() {
        LOGGER.info("Using serverListUpdater {}", serverListUpdater.getClass().getSimpleName());
        serverListUpdater.start(updateAction);
    }

    private String getIdentifier() {
        return this.getClientConfig().getClientName();
    }

    public void stopServerListRefreshing() {
        if (serverListUpdater != null) {
            serverListUpdater.stop();
        }
    }

    /**
     * 获取serverListImpl的服务列表然后进行过滤
     */
    @VisibleForTesting
    public void updateListOfServers() {
        List<T> servers = new ArrayList<T>();
        if (serverListImpl != null) {
            // 获取服务列表
            servers = serverListImpl.getUpdatedListOfServers();
            LOGGER.debug("List of Servers for {} obtained from Discovery client: {}",
                    getIdentifier(), servers);
            // 通过filter过滤服务列表
            if (filter != null) {
                servers = filter.getFilteredListOfServers(servers);
                LOGGER.debug("Filtered List of Servers for {} obtained from Discovery client: {}",
                        getIdentifier(), servers);
            }
        }
        // 更新服务列表
        updateAllServerList(servers);
    }

    /**
     * Update the AllServer list in the LoadBalancer if necessary and enabled
     * 
     * @param ls
     */
    protected void updateAllServerList(List<T> ls) {
        // other threads might be doing this - in which case, we pass
        // 标识ServerList正在更新服务列表
        if (serverListUpdateInProgress.compareAndSet(false, true)) {
            try {
                // 遍历新增的服务设置为活跃
                for (T s : ls) {
                    s.setAlive(true); // set so that clients can start using these
                                      // servers right away instead
                                      // of having to wait out the ping cycle.
                }
                // 调用自身覆盖的setServersList()方法
                setServersList(ls);
                // 调用父类的一次ping服务
                super.forceQuickPing();
            } finally {
                serverListUpdateInProgress.set(false);
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DynamicServerListLoadBalancer:");
        sb.append(super.toString());
        sb.append("ServerList:" + String.valueOf(serverListImpl));
        return sb.toString();
    }
    
    @Override 
    public void shutdown() {
        super.shutdown();
        stopServerListRefreshing();
    }


    @Monitor(name="LastUpdated", type=DataSourceType.INFORMATIONAL)
    public String getLastUpdate() {
        return serverListUpdater.getLastUpdate();
    }

    @Monitor(name="DurationSinceLastUpdateMs", type= DataSourceType.GAUGE)
    public long getDurationSinceLastUpdateMs() {
        return serverListUpdater.getDurationSinceLastUpdateMs();
    }

    @Monitor(name="NumUpdateCyclesMissed", type=DataSourceType.GAUGE)
    public int getNumberMissedCycles() {
        return serverListUpdater.getNumberMissedCycles();
    }

    @Monitor(name="NumThreads", type=DataSourceType.GAUGE)
    public int getCoreThreads() {
        return serverListUpdater.getCoreThreads();
    }
}
