# FollowerZooKeeperServer 

 前面已经分析过Follower 角色启动后的初始化流程了，接下来这一篇分析一下Follower 对于请求的处理。
 我们知道Follower在和Leader同步完数据(Follower#syncWithLeader)之后会启动FollowerZooKeeperServer，
 在FollowerZooKeeperServer启动过程中有一步很重要的步骤就是设置请求处理器(FollowerZooKeeperServer#setupRequestProcessors)，代码如下：
 
 ```
 
 // 下面即为请求处理链的构造
@Override
protected void setupRequestProcessors() {

    // 
    RequestProcessor finalProcessor = new FinalRequestProcessor(this);
    commitProcessor = new CommitProcessor(finalProcessor,
            Long.toString(getServerId()), true, getZooKeeperServerListener());
    commitProcessor.start();
    
    // 开始请求处理器
    firstProcessor = new FollowerRequestProcessor(this, commitProcessor);
    ((FollowerRequestProcessor) firstProcessor).start();
    
    // 同步请求处理器
    syncProcessor = new SyncRequestProcessor(this,
            new SendAckRequestProcessor((Learner)getFollower()));
    syncProcessor.start();
}
 ```
 
 从上面的构造器可以看出Follower 有两个请求处理链路：
 
 - firstProcessor(FollowerRequestProcessor) -> commitProcessor(CommitProcessor) -> finalProcessor(FinalRequestProcessor)
 - SyncRequestProcessor -> SendAckRequestProcessor
 
## 处理客户端请求

在zookeeper 中可以通过配置选择通过NIO/Netty的方式选择通讯方式(默认为NIO)，下面我们分别看下这两种场景下处理客户端请求。

### NIOServerCnxn 

在NIOServerCnxn 中通过 IOWorkRequest 来处理客户端的请求。具体对于请求处理的调用链路如下：

```
IOWorkRequest#doWork 
    -> NIOServerCnxn#doIO (这里会根据SelectionKey的状态处理读写)
        ->NIOServerCnxn#readPayload(处理读请求)
            ->NIOServerCnxn#readRequest
                ->ZooKeeperServer#processPacket
                    ->ZooKeeperServer#submitRequest
                
```

通过上面的调用链我们可以看出，对于请求的最终处理是交由 ZooKeeperServer#submitRequest的，请求链的处理也是从这里开始的，下面我们详细看下这个方法的实现：

```
public void submitRequest(Request si) {

    // 如果 firstProcessor 为空，说明系统初始化还没有完成(或者重新进行选举)，这里阻塞等待，知道系统初始化完成才开始处理。
    if (firstProcessor == null) {
        synchronized (this) {
            try {
                // Since all requests are passed to the request
                // processor it should wait for setting up the request
                // processor chain. The state will be updated to RUNNING
                // after the setup.
                while (state == State.INITIAL) {
                    wait(1000);
                }
            } catch (InterruptedException e) {
                LOG.warn("Unexpected interruption", e);
            }
            if (firstProcessor == null || state != State.RUNNING) {
                throw new RuntimeException("Not started");
            }
        }
    }
    try {
        // 校验客户端session是否过期，并更新session过期时间
        touch(si.cnxn);
        boolean validpacket = Request.isValid(si.type);
        if (validpacket) {
            
            // 通过请求链处理请求，首先从FollowerRequestProcessor 开始
            // FollowerRequestProcessor() 只做了一件事，就是检查是否要将本地session 升级为 global session，然后就请求放入请求队列中queuedRequests
            // 然后FollowerRequestProcessor线程再从它自身的队列中将请求交给 CommitProcessor
            
            // CommitProcessor 会 判断当前的请求是否需要 commit，如果不需要commit 就将请求直接转发给 FinalRequestProcessor
            // 如果需要commit，则先会将将请求放入nextPending 队列，然后在调用 FinalRequestProcessor 处理请求
            
            // FinalRequestProcessor
            
            firstProcessor.processRequest(si);
            if (si.cnxn != null) {
                incInProcess();
            }
        } else {
            LOG.warn("Received packet at server of unknown type " + si.type);
            new UnimplementedRequestProcessor().processRequest(si);
        }
    } catch (MissingSessionException e) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Dropping request: " + e.getMessage());
        }
    } catch (RequestProcessorException e) {
        LOG.error("Unable to process request:" + e.getMessage(), e);
    }
}
```

### NettyServerCnxn 





