# ZK 启动流程
## 启动类
zk的启动是从QuorumPeerMain类的main函数开始执行的，可以看到在main函数中仅调用QuorumPeerMain#initializeAndRun一个方法。

## initializeAndRun 方法分析
这个方法是zk执行初始化和开始运行的核心方法，这个方法不是很长，下面我们着重看一下这个方法的执行：

```
protected void initializeAndRun(String[] args)
    throws ConfigException, IOException, AdminServerException{
    QuorumPeerConfig config = new QuorumPeerConfig();
    
    // 为zk配置文件的路径，这里看出，配置参数只能有一个，不配或者多配最后都是以单机的方式运行zk
    if (args.length == 1) {
        config.parse(args[0]);
    }

    // Start and schedule the the purge task
    // 开始定时清理任务
    DatadirCleanupManager purgeMgr = new DatadirCleanupManager(config
            .getDataDir(), config.getDataLogDir(), config
            .getSnapRetainCount(), config.getPurgeInterval());
    purgeMgr.start();

    // 如果配置了以集群的方式运行，则读取配置文件并运行zk
    if (args.length == 1 && config.isDistributed()) {
        runFromConfig(config);
    } else {
        LOG.warn("Either no config or no quorum defined in config, running "
                + " in standalone mode");
        // there is only server in the quorum -- run as standalone
        // 单机方式运行zk
        ZooKeeperServerMain.main(args);
    }
}

```

### zk启动的清理任务分析

清理任务从DatadirCleanupManager 说起，数据目录清理的管理类，这个类的初始化需要资格参数：

```
    /**
     * Constructor of DatadirCleanupManager. It takes the parameters to schedule
     * the purge task.
     * 
     * @param snapDir 数据镜像目录
     * @param dataLogDir 事务日志目录
     * @param snapRetainCount 执行清理后保留的事务日志镜像个数
     * @param purgeInterval 每小时执行清理的次数
     */
    public DatadirCleanupManager(File snapDir, File dataLogDir, int snapRetainCount,
            int purgeInterval) {
        this.snapDir = snapDir;
        this.dataLogDir = dataLogDir;
        this.snapRetainCount = snapRetainCount;
        this.purgeInterval = purgeInterval;
    }

```
清理任务最终是委托给PurgeTask来执行，它会根据purgeInterval的值来计算出执行的周期，然后通过一个定时任务来触发的。
> 清理: 这部分内容暂时不作为重点内容，后期需要时在分析，具体任务的执行，参考PurgeTxnLog#purge方法。

### 根据配置文件中启动ZK

从标题可以看出，这部分的主要功能就是根据读取的配置文件来启动zk，核心代码如下：

```
public void runFromConfig(QuorumPeerConfig config)
            throws IOException, AdminServerException
{
  try {
      // 注册log4j 的JMX bean，这个可以通过环境变量zookeeper.jmx.log4j.disable来管理是否注册JMX bean
      ManagedUtil.registerLog4jMBeans();
  } catch (JMException e) {
      LOG.warn("Unable to register log4j JMX control", e);
  }

  LOG.info("Starting quorum peer");
  try {
      // 服务端的一个抽象工厂类，默认为NIO
      // 可以通过zookeeper.serverCnxnFactory属性来配置成为Netty的服务端
      ServerCnxnFactory cnxnFactory = null;
      ServerCnxnFactory secureCnxnFactory = null;

      // 当前服务端提供的客户端连接的端口地址
      if (config.getClientPortAddress() != null) {
          cnxnFactory = ServerCnxnFactory.createFactory();
          
          // 配置服务端工厂参数
          cnxnFactory.configure(config.getClientPortAddress(),
                  config.getMaxClientCnxns(),
                  false);
      }

      if (config.getSecureClientPortAddress() != null) {
          secureCnxnFactory = ServerCnxnFactory.createFactory();
          secureCnxnFactory.configure(config.getSecureClientPortAddress(),
                  config.getMaxClientCnxns(),
                  true);
      }

      // 获取新的参与者，并且初始化
      quorumPeer = getQuorumPeer();
      
      // 设置事务日志和快照日志目录
      quorumPeer.setTxnFactory(new FileTxnSnapLog(
                  config.getDataLogDir(),
                  config.getDataDir()));
      // 设置本地localSession，这个用途暂时还不清楚
      quorumPeer.enableLocalSessions(config.areLocalSessionsEnabled());
      quorumPeer.enableLocalSessionsUpgrading(
          config.isLocalSessionsUpgradingEnabled());
      //quorumPeer.setQuorumPeers(config.getAllMembers());
      
      // 设置选举类型，如果没有配置的话，默认为3
      quorumPeer.setElectionType(config.getElectionAlg());
      // 设置当前服务id
      quorumPeer.setMyid(config.getServerId());
      // 时钟周期时间，单位 milliseconds，默认为3000即3s
      quorumPeer.setTickTime(config.getTickTime());
      // session 的最大最小超时时间
      quorumPeer.setMinSessionTimeout(config.getMinSessionTimeout());
      quorumPeer.setMaxSessionTimeout(config.getMaxSessionTimeout());
      // 初始同步阶段使用时钟周期的个数 n * tickTime
      quorumPeer.setInitLimit(config.getInitLimit());
      // 在发出请求和收到确认阶段的时钟周期 n * tickTime
      quorumPeer.setSyncLimit(config.getSyncLimit());
      // 配置文件名称
      quorumPeer.setConfigFileName(config.getConfigFilename());
      // 设置zk的数据库，可以看出这个数据库的配置是基于前面配置的事务日志目录和快照目录生成的
      quorumPeer.setZKDatabase(new ZKDatabase(quorumPeer.getTxnFactory()));
      // 设置Quorum的校验器,这个也与zk的动态配置有关，详情以后再看
      quorumPeer.setQuorumVerifier(config.getQuorumVerifier(), false);
      if (config.getLastSeenQuorumVerifier()!=null) {
          quorumPeer.setLastSeenQuorumVerifier(config.getLastSeenQuorumVerifier(), false);
      }
      // 根据配置初始化ZK的数据库
      quorumPeer.initConfigInZKDatabase();
      // 设置当前服务器的工厂类
      quorumPeer.setCnxnFactory(cnxnFactory);
      quorumPeer.setSecureCnxnFactory(secureCnxnFactory);
      // 设置学习者类型，默认都是 PARTICIPANT
      quorumPeer.setLearnerType(config.getPeerType());
      // 默认是true，它用于观察者
      quorumPeer.setSyncEnabled(config.getSyncEnabled());
      // 对于(broadcast and fast leader election）是否监听所有ip列表，默认为false
      quorumPeer.setQuorumListenOnAllIPs(config.getQuorumListenOnAllIPs());

      // sets quorum sasl authentication configurations
      quorumPeer.setQuorumSaslEnabled(config.quorumEnableSasl);
      if(quorumPeer.isQuorumSaslAuthEnabled()){
          quorumPeer.setQuorumServerSaslRequired(config.quorumServerRequireSasl);
          quorumPeer.setQuorumLearnerSaslRequired(config.quorumLearnerRequireSasl);
          quorumPeer.setQuorumServicePrincipal(config.quorumServicePrincipal);
          quorumPeer.setQuorumServerLoginContext(config.quorumServerLoginContext);
          quorumPeer.setQuorumLearnerLoginContext(config.quorumLearnerLoginContext);
      }
      // 在connectionExecutors 中用于初始化quorum server连接的最大线程数
      quorumPeer.setQuorumCnxnThreadsSize(config.quorumCnxnThreadsSize);
      // 初始化quorumPeer，其实也就是初始化server 和learner的认证服务器
      quorumPeer.initialize();
      // 启动quorumPeer线程
      quorumPeer.start();
      quorumPeer.join();
  } catch (InterruptedException e) {
      // warn, but generally this is ok
      LOG.warn("Quorum Peer interrupted", e);
  }
}

```

至此，对于zk的配置启动的流程大致已经分析完了，下一节将详细分析启动过程中的每个点。

### QuorumPeer#start方法分析

从QuorumPeer的类结构图来看，它有Thread的父类，所以QuorumPeer#start最终还是要调用Thread#start的方法，下面我们具体来看下这个重写的start方法都做了些什么。

````
@Override
public synchronized void start() {
    if (!getView().containsKey(myid)) {
        throw new RuntimeException("My id " + myid + " not in the peer list");
     }
    // 载入本地的数据库
    loadDataBase();
    
    // 启动服务端工厂
    startServerCnxnFactory();
    try {
        // 启动管理服务
        adminServer.start();
    } catch (AdminServerException e) {
        LOG.warn("Problem starting AdminServer", e);
        System.out.println(e);
    }
    // 启动Leader节点的选举
    startLeaderElection();
    
    // 调用父类启动当前线程
    super.start();
}
````

#### 

上面的代码

### ServerCnxnFactory抽象工厂类解析

ZK 是作为服务端给客户端进行读写的功能，那么在与客户端通信的方式上提供了Java原生的NIOServerCnxnFactory和基于Netty的NettyServerCnxnFactory。
下面是ServerCnxnFactory提供的创建服务端工厂的功能，代码如下：

```
static public ServerCnxnFactory createFactory() throws IOException {
    // 如果没有配置zookeeper.serverCnxnFactory参数，则默认使用NIOServerCnxnFactory
    String serverCnxnFactoryName =
        System.getProperty(ZOOKEEPER_SERVER_CNXN_FACTORY);
    if (serverCnxnFactoryName == null) {
        serverCnxnFactoryName = NIOServerCnxnFactory.class.getName();
    }
    try {
    // 根据serverCnxnFactoryName 实例化ServerCnxnFactory 类。
        ServerCnxnFactory serverCnxnFactory = (ServerCnxnFactory) Class.forName(serverCnxnFactoryName)
                .getDeclaredConstructor().newInstance();
        LOG.info("Using {} as server connection factory", serverCnxnFactoryName);
        return serverCnxnFactory;
    } catch (Exception e) {
        IOException ioe = new IOException("Couldn't instantiate "
                + serverCnxnFactoryName);
        ioe.initCause(e);
        throw ioe;
    }
}
```








