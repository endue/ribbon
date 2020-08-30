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

import static java.util.Collections.singleton;

import com.google.common.collect.ImmutableList;
import com.netflix.client.ClientFactory;
import com.netflix.client.IClientConfigAware;
import com.netflix.client.PrimeConnections;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.Monitors;
import com.netflix.util.concurrent.ShutdownEnabledTimer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A basic implementation of the load balancer where an arbitrary list of
 * servers can be set as the server pool. A ping can be set to determine the
 * liveness of a server. Internally, this class maintains an "all" server list
 * and an "up" server list and use them depending on what the caller asks for.
 *
 * LoadBalancer的基本实现功能
 *  1.可以将任意服务列表加入到服务池中
 *  2.ping可以决定服务是否还具有活性
 *  3.内部还维护了两个服务列表，用来根据调用者的要求请求他们。一个保存所有服务，一个保存活跃服务的
 *
 * @author stonse
 * 
 */
public class BaseLoadBalancer extends AbstractLoadBalancer implements
        PrimeConnections.PrimeConnectionListener, IClientConfigAware {

    private static Logger logger = LoggerFactory.getLogger(BaseLoadBalancer.class);
    // 默认Rule
    private final static IRule DEFAULT_RULE = new RoundRobinRule();
    // 默认Ping策略
    private final static SerialPingStrategy DEFAULT_PING_STRATEGY = new SerialPingStrategy();
    private static final String DEFAULT_NAME = "default";
    private static final String PREFIX = "LoadBalancer_";
    // 初始化Rule
    protected IRule rule = DEFAULT_RULE;
    // 初始化Ping策略
    protected IPingStrategy pingStrategy = DEFAULT_PING_STRATEGY;

    protected IPing ping = null;
    // 所有服务列表
    @Monitor(name = PREFIX + "AllServerList", type = DataSourceType.INFORMATIONAL)
    protected volatile List<Server> allServerList = Collections
            .synchronizedList(new ArrayList<Server>());
    // 活跃服务列表
    @Monitor(name = PREFIX + "UpServerList", type = DataSourceType.INFORMATIONAL)
    protected volatile List<Server> upServerList = Collections
            .synchronizedList(new ArrayList<Server>());
    // 读写锁针对所有服务列表
    protected ReadWriteLock allServerLock = new ReentrantReadWriteLock();
    // 读写锁针对活跃服务列表
    protected ReadWriteLock upServerLock = new ReentrantReadWriteLock();

    protected String name = DEFAULT_NAME;
    // 用来定时执行Ping任务
    protected Timer lbTimer = null;
    // Ping间隔时间，默认10
    protected int pingIntervalSeconds = 10;
    // 一分钟最大Ping的次数
    protected int maxTotalPingTimeSeconds = 5;
    protected Comparator<Server> serverComparator = new ServerComparator();
    // 标记Ping定时任务是否正在执行
    protected AtomicBoolean pingInProgress = new AtomicBoolean(false);
    //  LoadBalancer统计信息
    protected LoadBalancerStats lbStats;

    private volatile Counter counter = Monitors.newCounter("LoadBalancer_ChooseServer");

    // 这两个一起用enablePrimingConnections为true的时候，需要初始化primeConnections
    // 添加服务到服务列表中的时候：
    //   服务默认readyToServe=true,如果设置enablePrimingConnections=true，则服务readyToServe=false。此时就需要primeConnections去做一些操作
    private PrimeConnections primeConnections;
    private volatile boolean enablePrimingConnections = false;
    
    private IClientConfig config;
    // 保存监听服务列表发生变更的时间
    private List<ServerListChangeListener> changeListeners = new CopyOnWriteArrayList<ServerListChangeListener>();
    // 保存监听服务状态变更的事件
    private List<ServerStatusChangeListener> serverStatusListeners = new CopyOnWriteArrayList<ServerStatusChangeListener>();

    /**
     * Default constructor which sets name as "default", sets null ping, and
     * {@link RoundRobinRule} as the rule.
     * <p>
     * This constructor is mainly used by {@link ClientFactory}. Calling this
     * constructor must be followed by calling {@link #init()} or
     * {@link #initWithNiwsConfig(IClientConfig)} to complete initialization.
     * This constructor is provided for reflection. When constructing
     * programatically, it is recommended to use other constructors.
     */
    public BaseLoadBalancer() {
        this.name = DEFAULT_NAME;
        this.ping = null;
        setRule(DEFAULT_RULE);
        // 设置Ping任务
        setupPingTask();
        // 初始化LoadBalancer统计信息
        lbStats = new LoadBalancerStats(DEFAULT_NAME);
    }

    public BaseLoadBalancer(String lbName, IRule rule, LoadBalancerStats lbStats) {
        this(lbName, rule, lbStats, null);
    }

    public BaseLoadBalancer(IPing ping, IRule rule) {
        this(DEFAULT_NAME, rule, new LoadBalancerStats(DEFAULT_NAME), ping);
    }

    public BaseLoadBalancer(IPing ping, IRule rule, IPingStrategy pingStrategy) {
        this(DEFAULT_NAME, rule, new LoadBalancerStats(DEFAULT_NAME), ping, pingStrategy);
    }

    public BaseLoadBalancer(String name, IRule rule, LoadBalancerStats stats,
            IPing ping) {
        this(name, rule, stats, ping, DEFAULT_PING_STRATEGY);
    }
    
    public BaseLoadBalancer(String name, IRule rule, LoadBalancerStats stats,
            IPing ping, IPingStrategy pingStrategy) {
	
        logger.debug("LoadBalancer [{}]:  initialized", name);
        
        this.name = name;
        this.ping = ping;
        this.pingStrategy = pingStrategy;
        setRule(rule);
        // 设置Ping任务
        setupPingTask();
        lbStats = stats;
        init();
    }

    public BaseLoadBalancer(IClientConfig config) {
        initWithNiwsConfig(config);
    }

    public BaseLoadBalancer(IClientConfig config, IRule rule, IPing ping) {
        initWithConfig(config, rule, ping, createLoadBalancerStatsFromConfig(config, ClientFactory::instantiateInstanceWithClientConfig));
    }

    void initWithConfig(IClientConfig clientConfig, IRule rule, IPing ping) {
        initWithConfig(clientConfig, rule, ping, createLoadBalancerStatsFromConfig(config, ClientFactory::instantiateInstanceWithClientConfig));
    }
    
    void initWithConfig(IClientConfig clientConfig, IRule rule, IPing ping, LoadBalancerStats stats) {
        this.config = clientConfig;
        this.name = clientConfig.getClientName();
        int pingIntervalTime = clientConfig.get(CommonClientConfigKey.NFLoadBalancerPingInterval, 30);
        int maxTotalPingTime = clientConfig.get(CommonClientConfigKey.NFLoadBalancerMaxTotalPingTime, 2);

        setPingInterval(pingIntervalTime);
        setMaxTotalPingTime(maxTotalPingTime);

        // cross associate with each other
        // i.e. Rule,Ping meet your container LB
        // LB, these are your Ping and Rule guys ...
        setRule(rule);
        setPing(ping);

        setLoadBalancerStats(stats);
        rule.setLoadBalancer(this);
        if (ping instanceof AbstractLoadBalancerPing) {
            ((AbstractLoadBalancerPing) ping).setLoadBalancer(this);
        }
        logger.info("Client: {} instantiated a LoadBalancer: {}", name, this);
        boolean enablePrimeConnections = clientConfig.getOrDefault(CommonClientConfigKey.EnablePrimeConnections);

        if (enablePrimeConnections) {
            this.setEnablePrimingConnections(true);
            PrimeConnections primeConnections = new PrimeConnections(
                    this.getName(), clientConfig);
            this.setPrimeConnections(primeConnections);
        }
        init();

    }
    
    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        try {
            initWithNiwsConfig(clientConfig, ClientFactory::instantiateInstanceWithClientConfig);
        } catch (Exception e) {
            throw new RuntimeException("Error initializing load balancer", e);
        }
    }

    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig, Factory factory) {
        String ruleClassName = clientConfig.getOrDefault(CommonClientConfigKey.NFLoadBalancerRuleClassName);
        String pingClassName = clientConfig.getOrDefault(CommonClientConfigKey.NFLoadBalancerPingClassName);
        try {
            IRule rule = (IRule)factory.create(ruleClassName, clientConfig);
            IPing ping = (IPing)factory.create(pingClassName, clientConfig);
            LoadBalancerStats stats = createLoadBalancerStatsFromConfig(clientConfig, factory);
            initWithConfig(clientConfig, rule, ping, stats);
        } catch (Exception e) {
            throw new RuntimeException("Error initializing load balancer", e);
        }
    }

    /**
     * 创建LoadBalancer统计信息
     * 默认为com.netflix.loadbalancer.LoadBalancerStats
     * @param clientConfig
     * @param factory
     * @return
     */
    private LoadBalancerStats createLoadBalancerStatsFromConfig(IClientConfig clientConfig, Factory factory) {
        String loadBalancerStatsClassName = clientConfig.getOrDefault(CommonClientConfigKey.NFLoadBalancerStatsClassName);
        try {
            return (LoadBalancerStats) factory.create(loadBalancerStatsClassName, clientConfig);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Error initializing configured LoadBalancerStats class - " + loadBalancerStatsClassName,
                    e);
        }
    }

    public void addServerListChangeListener(ServerListChangeListener listener) {
        changeListeners.add(listener);
    }
    
    public void removeServerListChangeListener(ServerListChangeListener listener) {
        changeListeners.remove(listener);
    }

    public void addServerStatusChangeListener(ServerStatusChangeListener listener) {
        serverStatusListeners.add(listener);
    }

    public void removeServerStatusChangeListener(ServerStatusChangeListener listener) {
        serverStatusListeners.remove(listener);
    }

    public IClientConfig getClientConfig() {
    	return config;
    }

    /**
     * 判断是否可以跳过ping，只有ping为null或者是DummyPing类才可以
     * @return
     */
    private boolean canSkipPing() {
        if (ping == null
                || ping.getClass().getName().equals(DummyPing.class.getName())) {
            // default ping, no need to set up timer
            return true;
        } else {
            return false;
        }
    }

    void setupPingTask() {
        // 判断是否可以跳过Ping
        if (canSkipPing()) {
            return;
        }
        // lbTimer不为空时，先取消运行
        if (lbTimer != null) {
            lbTimer.cancel();
        }
        // 初始化lbTimer
        lbTimer = new ShutdownEnabledTimer("NFLoadBalancer-PingTimer-" + name,
                true);
        // 新建PingTask，设置执行每10s执行一次
        lbTimer.schedule(new PingTask(), 0, pingIntervalSeconds * 1000);
        //
        forceQuickPing();
    }

    /**
     * Set the name for the load balancer. This should not be called since name
     * should be immutable after initialization. Calling this method does not
     * guarantee that all other data structures that depend on this name will be
     * changed accordingly.
     *
     * 设置LoadBalancer统计信息的名称
     */
    void setName(String name) {
        // and register
        this.name = name;
        if (lbStats == null) {
            lbStats = new LoadBalancerStats(name);
        } else {
            lbStats.setName(name);
        }
    }

    public String getName() {
        return name;
    }

    @Override
    public LoadBalancerStats getLoadBalancerStats() {
        return lbStats;
    }

    public void setLoadBalancerStats(LoadBalancerStats lbStats) {
        this.lbStats = lbStats;
    }

    public Lock lockAllServerList(boolean write) {
        Lock aproposLock = write ? allServerLock.writeLock() : allServerLock
                .readLock();
        aproposLock.lock();
        return aproposLock;
    }

    public Lock lockUpServerList(boolean write) {
        Lock aproposLock = write ? upServerLock.writeLock() : upServerLock
                .readLock();
        aproposLock.lock();
        return aproposLock;
    }

    /**
     * 更新Ping任务的执行事件
     * 更新后需调用setupPingTask()方法重新创建一个lbTimer
     * @param pingIntervalSeconds
     */
    public void setPingInterval(int pingIntervalSeconds) {
        if (pingIntervalSeconds < 1) {
            return;
        }

        this.pingIntervalSeconds = pingIntervalSeconds;
        if (logger.isDebugEnabled()) {
            logger.debug("LoadBalancer [{}]:  pingIntervalSeconds set to {}",
        	    name, this.pingIntervalSeconds);
        }
        setupPingTask(); // since ping data changed
    }

    public int getPingInterval() {
        return pingIntervalSeconds;
    }

    /*
     * Maximum time allowed for the ping cycle
     */
    public void setMaxTotalPingTime(int maxTotalPingTimeSeconds) {
        if (maxTotalPingTimeSeconds < 1) {
            return;
        }
        this.maxTotalPingTimeSeconds = maxTotalPingTimeSeconds;
        logger.debug("LoadBalancer [{}]: maxTotalPingTime set to {}", name, this.maxTotalPingTimeSeconds);

    }

    public int getMaxTotalPingTime() {
        return maxTotalPingTimeSeconds;
    }

    public IPing getPing() {
        return ping;
    }

    public IRule getRule() {
        return rule;
    }

    public boolean isPingInProgress() {
        return pingInProgress.get();
    }

    /* Specify the object which is used to send pings. */

    public void setPing(IPing ping) {
        if (ping != null) {
            if (!ping.equals(this.ping)) {
                this.ping = ping;
                setupPingTask(); // since ping data changed
            }
        } else {
            this.ping = null;
            // cancel the timer task
            lbTimer.cancel();
        }
    }

    /* Ignore null rules */

    public void setRule(IRule rule) {
        if (rule != null) {
            this.rule = rule;
        } else {
            /* default rule */
            this.rule = new RoundRobinRule();
        }
        if (this.rule.getLoadBalancer() != this) {
            this.rule.setLoadBalancer(this);
        }
    }

    /**
     * get the count of servers.
     * 
     * @param onlyAvailable
     *            if true, return only up servers.
     */
    public int getServerCount(boolean onlyAvailable) {
        if (onlyAvailable) {
            return upServerList.size();
        } else {
            return allServerList.size();
        }
    }

    /**
     * 添加服务列表
     */
    public void addServer(Server newServer) {
        if (newServer != null) {
            try {
                ArrayList<Server> newList = new ArrayList<Server>();

                newList.addAll(allServerList);
                newList.add(newServer);
                setServersList(newList);
            } catch (Exception e) {
                logger.error("LoadBalancer [{}]: Error adding newServer {}", name, newServer.getHost(), e);
            }
        }
    }

    /**
     * 添加服务列表
     */
    @Override
    public void addServers(List<Server> newServers) {
        if (newServers != null && newServers.size() > 0) {
            try {
                ArrayList<Server> newList = new ArrayList<Server>();
                newList.addAll(allServerList);
                newList.addAll(newServers);
                setServersList(newList);
            } catch (Exception e) {
                logger.error("LoadBalancer [{}]: Exception while adding Servers", name, e);
            }
        }
    }

    /*
     * 添加服务列表
     */
    void addServers(Object[] newServers) {
        if ((newServers != null) && (newServers.length > 0)) {

            try {
                ArrayList<Server> newList = new ArrayList<Server>();
                newList.addAll(allServerList);

                for (Object server : newServers) {
                    if (server != null) {
                        if (server instanceof String) {
                            server = new Server((String) server);
                        }
                        if (server instanceof Server) {
                            newList.add((Server) server);
                        }
                    }
                }
                setServersList(newList);
            } catch (Exception e) {
                logger.error("LoadBalancer [{}]: Exception while adding Servers", name, e);
            }
        }
    }

    /**
     * 添加服务，其他添加服务都会调用这个
     * 这个方法会将allServerLock直接设置成lsrv，所有需要在调用之前把旧的服务列表加入到lsrv中
     */
    public void setServersList(List lsrv) {
        Lock writeLock = allServerLock.writeLock();
        logger.debug("LoadBalancer [{}]: clearing server list (SET op)", name);
        // 标记新加入的服务
        ArrayList<Server> newServers = new ArrayList<Server>();
        writeLock.lock();
        try {
            // 标记所有新加入的服务，意义与newServers不通
            ArrayList<Server> allServers = new ArrayList<Server>();
            for (Object server : lsrv) {
                if (server == null) {
                    continue;
                }

                if (server instanceof String) {
                    server = new Server((String) server);
                }

                if (server instanceof Server) {
                    logger.debug("LoadBalancer [{}]:  addServer [{}]", name, ((Server) server).getId());
                    allServers.add((Server) server);
                } else {
                    throw new IllegalArgumentException(
                            "Type String or Server expected, instead found:"
                                    + server.getClass());
                }

            }
            // 新增加的额所有服务不等于之前的所有的服务，触发服务列表发生变更的事件
            boolean listChanged = false;
            if (!allServerList.equals(allServers)) {
                listChanged = true;
                if (changeListeners != null && changeListeners.size() > 0) {
                   List<Server> oldList = ImmutableList.copyOf(allServerList);
                   List<Server> newList = ImmutableList.copyOf(allServers);                   
                   for (ServerListChangeListener l: changeListeners) {
                       try {
                           l.serverListChanged(oldList, newList);
                       } catch (Exception e) {
                           logger.error("LoadBalancer [{}]: Error invoking server list change listener", name, e);
                       }
                   }
                }
            }
            // 如果enablePrimingConnections=true(默认false)，则新添加的服务readyToServe=false(默认true)
            // 遍历所有的服务，如果allServerList不包含这个服务，也就是新增加的服务，则设置相关属性
            if (isEnablePrimingConnections()) {
                // 遍历所有新加入的服务，排除已存在的服务
                for (Server server : allServers) {
                    if (!allServerList.contains(server)) {
                        server.setReadyToServe(false);
                        newServers.add((Server) server);
                    }
                }
                if (primeConnections != null) {
                    primeConnections.primeConnectionsAsync(newServers, this);
                }
            }
            // This will reset readyToServe flag to true on all servers
            // regardless whether
            // previous priming connections are success or not
            // 更新所有服务列表
            allServerList = allServers;
            // 可以跳过ping操作，直接设置所有服务都是活跃的
            if (canSkipPing()) {
                for (Server s : allServerList) {
                    s.setAlive(true);
                }
                upServerList = allServerList;
            // 不可以跳过ping操作并且服务列表中的服务发生了变更，触发ping操作
            } else if (listChanged) {
                forceQuickPing();
            }
        } finally {
            writeLock.unlock();
        }
    }

    /* List in string form. SETS, does not add. */
    void setServers(String srvString) {
        if (srvString != null) {

            try {
                String[] serverArr = srvString.split(",");
                ArrayList<Server> newList = new ArrayList<Server>();

                for (String serverString : serverArr) {
                    if (serverString != null) {
                        serverString = serverString.trim();
                        if (serverString.length() > 0) {
                            Server svr = new Server(serverString);
                            newList.add(svr);
                        }
                    }
                }
                setServersList(newList);
            } catch (Exception e) {
                logger.error("LoadBalancer [{}]: Exception while adding Servers", name, e);
            }
        }
    }

    /**
     * return the server
     * 
     * @param index
     * @param availableOnly
     */
    public Server getServerByIndex(int index, boolean availableOnly) {
        try {
            return (availableOnly ? upServerList.get(index) : allServerList
                    .get(index));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<Server> getServerList(boolean availableOnly) {
        return (availableOnly ? getReachableServers() : getAllServers());
    }

    @Override
    public List<Server> getReachableServers() {
        return Collections.unmodifiableList(upServerList);
    }

    @Override
    public List<Server> getAllServers() {
        return Collections.unmodifiableList(allServerList);
    }

    @Override
    public List<Server> getServerList(ServerGroup serverGroup) {
        switch (serverGroup) {
        case ALL:
            return allServerList;
        case STATUS_UP:
            return upServerList;
        case STATUS_NOT_UP:
            ArrayList<Server> notAvailableServers = new ArrayList<Server>(
                    allServerList);
            ArrayList<Server> upServers = new ArrayList<Server>(upServerList);
            notAvailableServers.removeAll(upServers);
            return notAvailableServers;
        }
        return new ArrayList<Server>();
    }

    public void cancelPingTask() {
        if (lbTimer != null) {
            lbTimer.cancel();
        }
    }

    /**
     * TimerTask that keeps runs every X seconds to check the status of each
     * server/node in the Server List
     * 
     * @author stonse
     * 
     */
    class PingTask extends TimerTask {
        public void run() {
            try {
                // 传入Ping策略，启动Ping服务
            	new Pinger(pingStrategy).runPinger();
            } catch (Exception e) {
                logger.error("LoadBalancer [{}]: Error pinging", name, e);
            }
        }
    }

    /**
     * Class that contains the mechanism to "ping" all the instances
     * 
     * @author stonse
     *
     */
    class Pinger {

        private final IPingStrategy pingerStrategy;

        public Pinger(IPingStrategy pingerStrategy) {
            this.pingerStrategy = pingerStrategy;
        }

        public void runPinger() throws Exception {
            // 标记Ping正在执行，防止并发
            if (!pingInProgress.compareAndSet(false, true)) { 
                return; // Ping in progress - nothing to do
            }
            
            // we are "in" - we get to Ping
            // 所有服务列表
            Server[] allServers = null;
            // 保存Ping结果
            boolean[] results = null;
            // 更新所有服务列表的锁
            Lock allLock = null;
            // 更新可用服务类别的锁
            Lock upLock = null;

            try {
                /*
                 * The readLock should be free unless an addServer operation is
                 * going on...
                 */
                // 获取所有服务列表的读锁
                allLock = allServerLock.readLock();
                // easy
                allLock.lock();
                allServers = allServerList.toArray(new Server[allServerList.size()]);
                allLock.unlock();
                // 标记所有服务数量
                int numCandidates = allServers.length;
                // 把所有的服务Ping一遍，并返回结果
                results = pingerStrategy.pingServers(ping, allServers);
                // 新活跃服务列表、状态发生变更服务列表
                final List<Server> newUpList = new ArrayList<Server>();
                final List<Server> changedServers = new ArrayList<Server>();
                // 遍历所有服务，根据状态进行划分
                // 1.活跃的保存到newUpList
                // 2.根据ping结果，更新所有服务的状态
                // 3.状态前后发生变更的保存到changedServers
                for (int i = 0; i < numCandidates; i++) {
                    boolean isAlive = results[i];
                    Server svr = allServers[i];
                    boolean oldIsAlive = svr.isAlive();

                    svr.setAlive(isAlive);

                    if (oldIsAlive != isAlive) {
                        changedServers.add(svr);
                        logger.debug("LoadBalancer [{}]:  Server [{}] status changed to {}", 
                    		name, svr.getId(), (isAlive ? "ALIVE" : "DEAD"));
                    }

                    if (isAlive) {
                        newUpList.add(svr);
                    }
                }
                // 获取活跃服务列表的写锁，然后更新
                upLock = upServerLock.writeLock();
                upLock.lock();
                upServerList = newUpList;
                upLock.unlock();
                // 发生服务状态变更事件
                notifyServerStatusChangeListener(changedServers);
            } finally {
                // Ping操作执行完，将标识设置为false
                pingInProgress.set(false);
            }
        }
    }

    /**
     * 通知服务状态变更事件发生
     * @param changedServers
     */
    private void notifyServerStatusChangeListener(final Collection<Server> changedServers) {
        if (changedServers != null && !changedServers.isEmpty() && !serverStatusListeners.isEmpty()) {
            for (ServerStatusChangeListener listener : serverStatusListeners) {
                try {
                    listener.serverStatusChanged(changedServers);
                } catch (Exception e) {
                    logger.error("LoadBalancer [{}]: Error invoking server status change listener", name, e);
                }
            }
        }
    }

    private final Counter createCounter() {
        return Monitors.newCounter("LoadBalancer_ChooseServer");
    }

    /*
     * Get the alive server dedicated to key
     *
     * 根据key选择服务
     *
     * @return the dedicated server
     */
    public Server chooseServer(Object key) {
        if (counter == null) {
            counter = createCounter();
        }
        counter.increment();
        if (rule == null) {
            return null;
        } else {
            try {
                return rule.choose(key);
            } catch (Exception e) {
                logger.warn("LoadBalancer [{}]:  Error choosing server for key {}", name, key, e);
                return null;
            }
        }
    }

    /* Returns either null, or "server:port/servlet" */
    public String choose(Object key) {
        if (rule == null) {
            return null;
        } else {
            try {
                Server svr = rule.choose(key);
                return ((svr == null) ? null : svr.getId());
            } catch (Exception e) {
                logger.warn("LoadBalancer [{}]:  Error choosing server", name, e);
                return null;
            }
        }
    }

    public void markServerDown(Server server) {
        if (server == null || !server.isAlive()) {
            return;
        }

        logger.error("LoadBalancer [{}]:  markServerDown called on [{}]", name, server.getId());
        server.setAlive(false);
        // forceQuickPing();

        notifyServerStatusChangeListener(singleton(server));
    }

    public void markServerDown(String id) {
        boolean triggered = false;

        id = Server.normalizeId(id);

        if (id == null) {
            return;
        }

        Lock writeLock = upServerLock.writeLock();
    	writeLock.lock();
        try {
            final List<Server> changedServers = new ArrayList<Server>();

            for (Server svr : upServerList) {
                if (svr.isAlive() && (svr.getId().equals(id))) {
                    triggered = true;
                    svr.setAlive(false);
                    changedServers.add(svr);
                }
            }

            if (triggered) {
                logger.error("LoadBalancer [{}]:  markServerDown called for server [{}]", name, id);
                notifyServerStatusChangeListener(changedServers);
            }

        } finally {
            writeLock.unlock();
        }
    }

    /*
     * Force an immediate ping, if we're not currently pinging and don't have a
     * quick-ping already scheduled.
     */
    public void forceQuickPing() {
        if (canSkipPing()) {
            return;
        }
        logger.debug("LoadBalancer [{}]:  forceQuickPing invoking", name);
        
        try {
        	new Pinger(pingStrategy).runPinger();
        } catch (Exception e) {
            logger.error("LoadBalancer [{}]: Error running forceQuickPing()", name, e);
        }
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{NFLoadBalancer:name=").append(this.getName())
                .append(",current list of Servers=").append(this.allServerList)
                .append(",Load balancer stats=")
                .append(this.lbStats.toString()).append("}");
        return sb.toString();
    }

    /**
     * Register with monitors and start priming connections if it is set.
     */
    protected void init() {
        Monitors.registerObject("LoadBalancer_" + name, this);
        // register the rule as it contains metric for available servers count
        Monitors.registerObject("Rule_" + name, this.getRule());
        if (enablePrimingConnections && primeConnections != null) {
            primeConnections.primeConnections(getReachableServers());
        }
    }

    public final PrimeConnections getPrimeConnections() {
        return primeConnections;
    }

    public final void setPrimeConnections(PrimeConnections primeConnections) {
        this.primeConnections = primeConnections;
    }

    /**
     * 这个方法在enablePrimingConnections=true时生效
     * @param s
     * @param lastException
     */
    @Override
    public void primeCompleted(Server s, Throwable lastException) {
        s.setReadyToServe(true);
    }

    public boolean isEnablePrimingConnections() {
        return enablePrimingConnections;
    }

    public final void setEnablePrimingConnections(
            boolean enablePrimingConnections) {
        this.enablePrimingConnections = enablePrimingConnections;
    }
    
    public void shutdown() {
        cancelPingTask();
        if (primeConnections != null) {
            primeConnections.shutdown();
        }
        Monitors.unregisterObject("LoadBalancer_" + name, this);
        Monitors.unregisterObject("Rule_" + name, this.getRule());
    }

    /**
     * Default implementation for <c>IPingStrategy</c>, performs ping
     * serially, which may not be desirable, if your <c>IPing</c>
     * implementation is slow, or you have large number of servers.
     */
    private static class SerialPingStrategy implements IPingStrategy {

        @Override
        public boolean[] pingServers(IPing ping, Server[] servers) {
            int numCandidates = servers.length;
            boolean[] results = new boolean[numCandidates];

            logger.debug("LoadBalancer:  PingTask executing [{}] servers configured", numCandidates);
            // 循环服务礼拜，执行ping操作
            for (int i = 0; i < numCandidates; i++) {
                results[i] = false; /* Default answer is DEAD. */
                try {
                    // NOTE: IFF we were doing a real ping
                    // assuming we had a large set of servers (say 15)
                    // the logic below will run them serially
                    // hence taking 15 times the amount of time it takes
                    // to ping each server
                    // A better method would be to put this in an executor
                    // pool
                    // But, at the time of this writing, we dont REALLY
                    // use a Real Ping (its mostly in memory eureka call)
                    // hence we can afford to simplify this design and run
                    // this
                    // serially
                    if (ping != null) {
                        results[i] = ping.isAlive(servers[i]);
                    }
                } catch (Exception e) {
                    logger.error("Exception while pinging Server: '{}'", servers[i], e);
                }
            }
            return results;
        }
    }
}
