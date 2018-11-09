
# ServerCnxnFactory

这篇是接着上一篇的数据恢复的，是因为zk在从snapshot和transaction log 恢复数据之后，接下来就是通过服务端的抽象工厂启动应用线程了。
ServerCnxnFactory是一个抽象工厂，它有两个具体的工厂类：NIOServerCnxnFactory 和 NettyServerCnxnFactory。在实际应用中根据配置选择对应的实现，下面我们先看下工厂的创建。


## 1、ServerCnxnFactory#createFactory

这段代码主要是根据系统配置实例化具体的服务端工厂。

```
static public ServerCnxnFactory createFactory() throws IOException {
    
    // 优先取系统配置 zookeeper.serverCnxnFactory，获取实例化工厂的名称
    String serverCnxnFactoryName =
        System.getProperty(ZOOKEEPER_SERVER_CNXN_FACTORY);
    // 如果没有配置，则使用 NIOServerCnxnFactory
    if (serverCnxnFactoryName == null) {
        serverCnxnFactoryName = NIOServerCnxnFactory.class.getName();
    }
    try {
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

## 2、配置-ServerCnxnFactory#configure

服务线程工程的配置是在初始化配置。

### 2.1、NIOServerCnxnFactory#configure

下面为NIO的配置代码：

```
@Override
public void configure(InetSocketAddress addr, int maxcc, boolean secure) throws IOException {
    
    // NIO 不支持SSL 认证
    if (secure) {
        throw new UnsupportedOperationException("SSL isn't supported in NIOServerCnxn");
    }
    configureSaslLogin();
    
    // 最大客户端连接数
    maxClientCnxns = maxcc;
    
    // session超时时间，如果session超时，会被expired connection thread 清理
    sessionlessCnxnTimeout = Integer.getInteger(
        ZOOKEEPER_NIO_SESSIONLESS_CNXN_TIMEOUT, 10000);
    // We also use the sessionlessCnxnTimeout as expiring interval for
    // cnxnExpiryQueue. These don't need to be the same, but the expiring
    // interval passed into the ExpiryQueue() constructor below should be
    // less than or equal to the timeout.
    
    // 超时队列的间隔
    cnxnExpiryQueue =
        new ExpiryQueue<NIOServerCnxn>(sessionlessCnxnTimeout);
        
    // 连接超时清理线程
    expirerThread = new ConnectionExpirerThread();

    // 获取当前CPU核数
    int numCores = Runtime.getRuntime().availableProcessors();
    // 32 cores sweet spot seems to be 4 selector threads
    
    // 如果没有配置selector thread 线程数，那么这个值为
    numSelectorThreads = Integer.getInteger(
        ZOOKEEPER_NIO_NUM_SELECTOR_THREADS,
        Math.max((int) Math.sqrt((float) numCores/2), 1));
    if (numSelectorThreads < 1) {
        throw new IOException("numSelectorThreads must be at least 1");
    }

    // worker thread 数量，默认为2 * numCore
    numWorkerThreads = Integer.getInteger(
        ZOOKEEPER_NIO_NUM_WORKER_THREADS, 2 * numCores);
    
    // 
    workerShutdownTimeoutMS = Long.getLong(
        ZOOKEEPER_NIO_SHUTDOWN_TIMEOUT, 5000);
        
    // 初始化 selector 线程
    for(int i=0; i<numSelectorThreads; ++i) {
        selectorThreads.add(new SelectorThread(i));
    }
    
    this.ss = ServerSocketChannel.open();
    
    // 
    ss.socket().setReuseAddress(true);
    LOG.info("binding to port " + addr);
    ss.socket().bind(addr);
    
    // 配置非阻塞模式
    ss.configureBlocking(false);
    // 开启接受线程
    acceptThread = new AcceptThread(ss, addr, selectorThreads);
}
```

## 2.2、 NettyServerCnxnFactory#configure

下面是基于Netty的配置：

```
@Override
public void configure(InetSocketAddress addr, int maxClientCnxns, boolean secure)
        throws IOException
{
    // 配置ssl登录，这个目前默认使用的是父类的配置方法
    configureSaslLogin();
    localAddress = addr;
    this.maxClientCnxns = maxClientCnxns;
    this.secure = secure;
}
```

## 3、ServerCnxnFactory#start

接下来看看ServerCnxnFactory的start方法，它是个抽象方法，具体实现由两个子类来实现。

### 3.1、NIOServerCnxnFactory#start

这个方法主要是启动NIOServerCnxnFactory中对应的各个线程。关于它的线程模型我们在下一节看

```
public void start() {
    stopped = false;
    if (workerPool == null) {
        
        // 启动 Worker线程池，核心线程数为numWorkerThreads 
        workerPool = new WorkerService(
            "NIOWorker", numWorkerThreads, false);
    }
    
    // 启动 SelectorThread 线程
    for(SelectorThread thread : selectorThreads) {
        if (thread.getState() == Thread.State.NEW) {
            thread.start();
        }
    }
    // ensure thread is started once and only once
    if (acceptThread.getState() == Thread.State.NEW) {
        acceptThread.start();
    }
    if (expirerThread.getState() == Thread.State.NEW) {
        expirerThread.start();
    }
}
```

### 3.2、NettyServerCnxnFactory#start 

```

@Override
public void start() {
    LOG.info("binding to port " + localAddress);
    // 直接绑定地址
    parentChannel = bootstrap.bind(localAddress);
}
```
上面大概分析了基于 NIO 和 Netty的配置和启动，相对于Netty，NIO的配置和启动都相对比较复杂，下面我们着重分析NIOServerCnxnFactory。

## 4、NIOServerCnxnFactory 

使用NIO non-blocking socket 实现多线程版本的ServerCnxnFactory。通过队列实现线程间的通信。

### 4.1 、线程模型
线程模型如下：

> 1、1   个accept 线程。接受新的连接并把它分配给一个selector 线程
>
> 2、1-N 个selector 线程。它会选择N连接中的一个。工厂支持N个selector 线程的原因是当一个selector接受大量连接的时候，select()会成为性能瓶颈
>
> 3、0-M 个socket I/O worker 线程。它们执行基本的读写。如果没有配置worker 线程，那么selector 会直接执行IO操作。
>
> 4、1   个connection expiration 线程。它会关闭空闲连接。

对于线程模型中线程数的配置，可通过如下配置：

```
// session 连接超时时间，默认 10000(10s)
zookeeper.nio.sessionlessCnxnTimeout

// selector 的线程数量，默认 sqrt(numCores/2) numCores：机器核数，最少1个
zookeeper.nio.numSelectorThreads

// worker 线程数量，默认2 * numCores
zookeeper.nio.numWorkerThreads

// direct Buffer 大小，默认 64k
zookeeper.nio.directBufferBytes

// worker pool 关闭的超时时间 
zookeeper.nio.shutdownTimeout
```

在一个32核的机器上，一个典型的配置：1 accept thread,1 connection expiration thread, 4 selector threads, and 64 worker threads.

### 4.2 WorkerService

```

/**
 * @param name                  worker threads are named <name>Thread-##
 * @param numThreads            number of worker threads (0 - N)
 *                              If 0, scheduled work is run immediately by
 *                              the calling thread.
 * @param useAssignableThreads  if true，那么会创建 numThreads 个 线程池，每个线程池线程个数为1
 */
public WorkerService(String name, int numThreads,
                     boolean useAssignableThreads) {
    // 线程名称前缀                 
    this.threadNamePrefix = (name == null ? "" : name) + "Thread";
    
    // 工作线程数
    this.numWorkerThreads = numThreads;
    this.threadsAreAssignable = useAssignableThreads;
    
    // 创建 线程池
    start();
}

从下面代码可以看出，如果配置了线程可以被单独制定，那么将会创建 numWorkerThreads 个线程池(线程数量为1)，否则，就开启一个有 numWorkerThreads 个核心线程的线程池。

public void start() {
    if (numWorkerThreads > 0) {
        if (threadsAreAssignable) {
            for(int i = 1; i <= numWorkerThreads; ++i) {
                workers.add(Executors.newFixedThreadPool(
                    1, new DaemonThreadFactory(threadNamePrefix, i)));
            }
        } else {
            workers.add(Executors.newFixedThreadPool(
                numWorkerThreads, new DaemonThreadFactory(threadNamePrefix)));
        }
    }
    stopped = false;
}

```

### 2.2 AcceptThread 

接受线程，它会接受新的连接并通过轮询的方式将它们分发给 SelectorThread。它的构造函数如下：

```
// 构造函数中的参数是在NIO 参数配置时设置的
public AcceptThread(ServerSocketChannel ss, InetSocketAddress addr,
        Set<SelectorThread> selectorThreads) throws IOException {
    super("NIOServerCxnFactory.AcceptThread:" + addr);
    this.acceptSocket = ss;
    
    // acceptSocket 注册 SelectionKey.OP_ACCEPT 
    this.acceptKey =
        acceptSocket.register(selector, SelectionKey.OP_ACCEPT);
    this.selectorThreads = Collections.unmodifiableList(
        new ArrayList<SelectorThread>(selectorThreads));
    selectorIterator = this.selectorThreads.iterator();
}

```
下面看下AcceptThread的主要功能，下面看下它的select方法：

```
// 下面的代码是select的简化，从代码可以看出，如果在处理失败，会先暂停10s钟
private void select() {
    
    while (!stopped && selectedKeys.hasNext()) {
        if (key.isAcceptable()) {
            // 如果处理成功
            if (!doAccept()) {
                // If unable to pull a new connection off the accept
                // queue, pause accepting to give us time to free
                // up file descriptors and so the accept thread
                // doesn't spin in a tight loop.
                pauseAccept(10);
            }
        }
    }
}

// 处理请求
private boolean doAccept() {
    boolean accepted = false;
    SocketChannel sc = null;
    try {
        sc = acceptSocket.accept();
        accepted = true;
        InetAddress ia = sc.socket().getInetAddress();
        
        // 获取当前客户端的连接数
        int cnxncount = getClientCnxnCount(ia);
        
        if (maxClientCnxns > 0 && cnxncount >= maxClientCnxns){
            throw new IOException("Too many connections from " + ia
                                  + " - max is " + maxClientCnxns );
        }

        LOG.info("Accepted socket connection from "
                 + sc.socket().getRemoteSocketAddress());
        sc.configureBlocking(false);

        // Round-robin assign this connection to a selector thread
        // 使用轮询的方式去使用 SelectorThread 
        if (!selectorIterator.hasNext()) {
            selectorIterator = selectorThreads.iterator();
        }
        SelectorThread selectorThread = selectorIterator.next();
        
        // 调用 SelectorThread#addAcceptedConnection 处理当前的客户端连接
        if (!selectorThread.addAcceptedConnection(sc)) {
            throw new IOException(
                "Unable to add connection to selector queue"
                + (stopped ? " (shutdown in progress)" : ""));
        }
        // 持久化错误日志
        acceptErrorLogger.flush();
    } catch (IOException e) {
        // accept, maxClientCnxns, configureBlocking
        acceptErrorLogger.rateLimitLog(
            "Error accepting new connection: " + e.getMessage());
            
        // 关闭当前客户端连接
        fastCloseSock(sc);
    }
    return accepted;
}


```

```

```


### 3.3 SelectorThread 

如前所述，SelectorThread主要是接受来自AcceptThread的连接并且负责为它们选择一个准备好的IO连接。
如果用户配置了worker thread pool，在处理IO 连接的时候，SelectorThread 通过清除它的订阅的操作类型来将它从selection中移出。
当work 完成之后，连接将会被置于准备队列中，并且恢复它订阅的操作，重新选择。
如果没有worker thread pool，SelectorThread 它会直接执行IO操作。

```
这里id是根据线程创建的次序确定的，最小为0，最大为 numSelectorThreads - 1

public SelectorThread(int id) throws IOException {
    super("NIOServerCxnFactory.SelectorThread-" + id);
    this.id = id;
    //接受队列
    acceptedQueue = new LinkedBlockingQueue<SocketChannel>();
    更新SelectionKey队列
    updateQueue = new LinkedBlockingQueue<SelectionKey>();
}

```

下面着重看下SelectorThread#run方法的实现：

```
public void run() {
    try {
        while (!stopped) {
            try {
                
                // 通过select 方法将，所有有效的可读写的交由handleIO 去处理，这个方法分析在下面
                select();
                processAcceptedConnections();
                processInterestOpsUpdateRequests();
            } catch (RuntimeException e) {
                LOG.warn("Ignoring unexpected runtime exception", e);
            } catch (Exception e) {
                LOG.warn("Ignoring unexpected exception", e);
            }
        }

        // Close connections still pending on the selector. Any others
        // with in-flight work, let drain out of the work queue.
        for (SelectionKey key : selector.keys()) {
            NIOServerCnxn cnxn = (NIOServerCnxn) key.attachment();
            if (cnxn.isSelectable()) {
                cnxn.close();
            }
            cleanupSelectionKey(key);
        }
        SocketChannel accepted;
        while ((accepted = acceptedQueue.poll()) != null) {
            fastCloseSock(accepted);
        }
        updateQueue.clear();
    } finally {
        closeSelector();
        // This will wake up the accept thread and the other selector
        // threads, and tell the worker thread pool to begin shutdown.
        NIOServerCnxnFactory.this.stop();
        LOG.info("selector thread exitted run method");
    }
}
```


```
private void select() {
    try {
        
        selector.select();

        Set<SelectionKey> selected = selector.selectedKeys();
        ArrayList<SelectionKey> selectedList =
            new ArrayList<SelectionKey>(selected);
        Collections.shuffle(selectedList);
        Iterator<SelectionKey> selectedKeys = selectedList.iterator();
        while(!stopped && selectedKeys.hasNext()) {
            SelectionKey key = selectedKeys.next();
            selected.remove(key);

            if (!key.isValid()) {
                cleanupSelectionKey(key);
                continue;
            }
            if (key.isReadable() || key.isWritable()) {
                handleIO(key);
            } else {
                LOG.warn("Unexpected ops in select " + key.readyOps());
            }
        }
    } catch (IOException e) {
        LOG.warn("Ignoring IOException while selecting", e);
    }
}
```

```
/**
 * Schedule I/O for processing on the connection associated with
 * the given SelectionKey. If a worker thread pool is not being used,
 * I/O is run directly by this thread.
 */
private void handleIO(SelectionKey key) {
    
    // IOWorkRequest是一个小的包装类，它用于在 WorkerService#run 中调用doIO的操作
    IOWorkRequest workRequest = new IOWorkRequest(this, key);
    NIOServerCnxn cnxn = (NIOServerCnxn) key.attachment();

    // Stop selecting this key while processing on its
    // connection
    cnxn.disableSelectable();
    key.interestOps(0);
    touchCnxn(cnxn);
    workerPool.schedule(workRequest);
}
```

```
public boolean addAcceptedConnection(SocketChannel accepted) {

    // 现将收到的客户端连接加入到acceptedQueue队列中
    if (stopped || !acceptedQueue.offer(accepted)) {
        return false;
    }
    // 唤醒selector
    wakeupSelector();
    return true;
}
```

将当前的信息封装到NIOServerCnxn，然后由它处理和客户端的通信
```
private void processAcceptedConnections() {
    SocketChannel accepted;
    while (!stopped && (accepted = acceptedQueue.poll()) != null) {
        SelectionKey key = null;
        try {
            // 注册SelectionKey.OP_READ事件
            key = accepted.register(selector, SelectionKey.OP_READ);
            
            // 当前信息封装到 NIOServerCnxn
            NIOServerCnxn cnxn = createConnection(accepted, key, this);
            key.attach(cnxn);
            // 把当前的信息加入到 ipMap 和 cnxns 中，同时会更新本次客户的session的过期时间，同时也会移出已经过期的session 
            // addCnxn -> touchCnxn -> ExpiryQueue#update 方法
            addCnxn(cnxn);
        } catch (IOException e) {
            // register, createConnection
            cleanupSelectionKey(key);
            fastCloseSock(accepted);
        }
    }
}
```



