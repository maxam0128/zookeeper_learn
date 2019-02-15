# Zk选举实现

通过前一篇对ZK选举过程中的类可知，FastLeaderElection是3.4.0版本后选举的唯一实现(实现Election接口)，
下面我们就看下选举的核心方法lookForLeader的实现。

## 1、注册当前选举JMXBean。

```$xslt

try {
    self.jmxLeaderElectionBean = new LeaderElectionBean();
    MBeanRegistry.getInstance().register(
            self.jmxLeaderElectionBean, self.jmxLocalPeerBean);
} catch (Exception e) {
    LOG.warn("Failed to register with JMX", e);
    self.jmxLeaderElectionBean = null;
}
```

## 2、更新当前选票信息

```$xslt

// 收到的选票信息
HashMap<Long, Vote> recvset = new HashMap<Long, Vote>();

// 发送的选举信息
HashMap<Long, Vote> outofelection = new HashMap<Long, Vote>();

int notTimeout = finalizeWait;

synchronized(this){
    
    // 逻辑时钟自增，每进行一次选举，逻辑时钟就自增一次
    logicalclock.incrementAndGet();
    
    // 用当前节点的初始信息更新为选举信息
    updateProposal(getInitId(), getInitLastLoggedZxid(), getPeerEpoch());
}

LOG.info("New election. My id =  " + self.getId() +
        ", proposed zxid=0x" + Long.toHexString(proposedZxid));

// 以广播的方式发送选票信息
sendNotifications();
```
### 2.1 更新选票信息 

```$xslt

synchronized void updateProposal(long leader, long zxid, long epoch){
    if(LOG.isDebugEnabled()){
        LOG.debug("Updating proposal: " + leader + " (newleader), 0x"
                + Long.toHexString(zxid) + " (newzxid), " + proposedLeader
                + " (oldleader), 0x" + Long.toHexString(proposedZxid) + " (oldzxid)");
    }
    proposedLeader = leader;
    proposedZxid = zxid;
    proposedEpoch = epoch;
}
```
### 2.2 发送选票通知
```$xslt

// 发送选票通知
private void sendNotifications() {
    
    // 这里会获取到所有的投票节点，然后依次给他们发送通知
    for (long sid : self.getCurrentAndNextConfigVoters()) {
        QuorumVerifier qv = self.getQuorumVerifier();
        ToSend notmsg = new ToSend(ToSend.mType.notification,
                proposedLeader,
                proposedZxid,
                logicalclock.get(),
                QuorumPeer.ServerState.LOOKING,
                sid,
                proposedEpoch, qv.toString().getBytes());
        if(LOG.isDebugEnabled()){
            LOG.debug("Sending Notification: " + proposedLeader + " (n.leader), 0x"  +
                  Long.toHexString(proposedZxid) + " (n.zxid), 0x" + Long.toHexString(logicalclock.get())  +
                  " (n.round), " + sid + " (recipient), " + self.getId() +
                  " (myid), 0x" + Long.toHexString(proposedEpoch) + " (n.peerEpoch)");
        }
        // 这里将通知先放到通知队列，随后交由QuorumCnxManager#SendWorker 完成消息发送
        sendqueue.offer(notmsg);
    }
}
```

## 3. 查找首节点

接下来主要是通过一个循环来交换各个节点的选票信息，直到选出首节点。

### 3.1 获取其他的选票信息

```$xslt

// 从接受消息队列获取一条消息
//notTimeout: 初始为200，如果每次获取超时，该值会翻倍，最大为60000
Notification n = recvqueue.poll(notTimeout,
                        TimeUnit.MILLISECONDS);

// 如果当前还没有收到其他节点发送的投票通知
if(n == null){
    
    // 判断管理节点是否已经将初始化的选票通知发送给其他节点
    if(manager.haveDelivered()){
        // 重新发送当前节点投票信息
        sendNotifications();
    } else {
        
        // 如果没有发送，则重新尝试连接所有节点
        manager.connectAll();
    }

    // 指数级的增长超时时间
    int tmpTimeOut = notTimeout*2;
    notTimeout = (tmpTimeOut < maxNotificationInterval?
            tmpTimeOut : maxNotificationInterval);
    LOG.info("Notification time out: " + notTimeout);
} 
```

### 3.2 处理其他节点的投票信息 

其他节点发送投票消息必须是具有选举资格的节点(投票节点和选举节点)，这里通过下面两个方法来校验：

> validVoter(n.sid) && validVoter(n.leader)

接下来会根据发送投票信息节点的状态分别来处理。

#### 3.2.1 其他节点为选举状态

```$xslt

case LOOKING:

    //if notification > current，那么替换当前节点的Proposal信息，然后重新发送信息
    if (n.electionEpoch > logicalclock.get()) {
        logicalclock.set(n.electionEpoch);
        // 清空之前的投票信息，因为之前的投票落后于当前的投票，则说明之前的投票已经无效
        recvset.clear();
        
        // 如果收到信息的节点优先级(epoch,zxid,sid)比当前节点高,那么更新当前节点的选举信息，然后发送更新后的选举通知
        if(totalOrderPredicate(n.leader, n.zxid, n.peerEpoch,
                getInitId(), getInitLastLoggedZxid(), getPeerEpoch())) {
            updateProposal(n.leader, n.zxid, n.peerEpoch);
        } else {
            updateProposal(getInitId(),
                    getInitLastLoggedZxid(),
                    getPeerEpoch());
        }
        // 发送当前节点的选举信息通知
        sendNotifications();
    } else if (n.electionEpoch < logicalclock.get()) { // if electionEpoch < logicalclock,
        if(LOG.isDebugEnabled()){
            LOG.debug("Notification election epoch is smaller than logicalclock. n.electionEpoch = 0x"
                    + Long.toHexString(n.electionEpoch)
                    + ", logicalclock=0x" + Long.toHexString(logicalclock.get()));
        }
        break;
    } else if (totalOrderPredicate(n.leader, n.zxid, n.peerEpoch,
            proposedLeader, proposedZxid, proposedEpoch)) { // 节点比较，然后决定是否发送选举通知
        updateProposal(n.leader, n.zxid, n.peerEpoch);
        sendNotifications();
    }
    
    if(LOG.isDebugEnabled()){
        LOG.debug("Adding vote: from=" + n.sid +
                ", proposed leader=" + n.leader +
                ", proposed zxid=0x" + Long.toHexString(n.zxid) +
                ", proposed election epoch=0x" + Long.toHexString(n.electionEpoch));
    }
    
    // 将投票信息放入recvset，该集合会保存收到的所有投票信息
    recvset.put(n.sid, new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch));
    
    // 如果大多数(n/2 + 1)节点的投票都是当前节点
    if (termPredicate(recvset,
            new Vote(proposedLeader, proposedZxid,
                    logicalclock.get(), proposedEpoch))) {
    
        // 校验是否提议的首节点有变化
        while((n = recvqueue.poll(finalizeWait,
                TimeUnit.MILLISECONDS)) != null){
            // 如果有新的投票节点，那将这次的节点数据继续放回队列进行下一轮的选举
            if(totalOrderPredicate(n.leader, n.zxid, n.peerEpoch,
                    proposedLeader, proposedZxid, proposedEpoch)){
                recvqueue.put(n);
                break;
            }
        }
    
        /*
         * This predicate is true once we don't read any new
         * relevant message from the reception queue
         */
         // 如果没有新的投票首节点
        if (n == null) {
            // 将当前节点的状态设置为ServerState.LEADING 或 ServerState.FOLLOWING
            self.setPeerState((proposedLeader == self.getId()) ?
                    ServerState.LEADING: learningState());
            
            Vote endVote = new Vote(proposedLeader,
                    proposedZxid, proposedEpoch);
                    
            // 清空接受通知队列
            leaveInstance(endVote);
            
            // 返回最终的首节点投票
            return endVote;
        }
    }
```

投票节点和当前节点比较。如果投票节点获胜则返回true，当前节点获胜返回false

```$xslt
 
protected boolean totalOrderPredicate(long newId, long newZxid, long newEpoch, long curId, long curZxid, long curEpoch) {
    LOG.debug("id: " + newId + ", proposed id: " + curId + ", zxid: 0x" +
            Long.toHexString(newZxid) + ", proposed zxid: 0x" + Long.toHexString(curZxid));
    
    // 如果投票节点没有配置投票权重，则返回
    if(self.getQuorumVerifier().getWeight(newId) == 0){
        return false;
    }

    // 选主节点的优先级顺序 epoch > zxid > sid
    /*
     * We return true if one of the following three cases hold:
     * 1- New epoch is higher
     * 2- New epoch is the same as current epoch, but new zxid is higher
     * 3- New epoch is the same as current epoch, new zxid is the same
     *  as current zxid, but server id is higher.
     */
    return ((newEpoch > curEpoch) ||
            ((newEpoch == curEpoch) &&
            ((newZxid > curZxid) || ((newZxid == curZxid) && (newId > curId)))));
}
```

校验投票vote，是否已经有超过半数的投票

```$xslt

private boolean termPredicate(HashMap<Long, Vote> votes, Vote vote) {

    SyncedLearnerTracker voteSet = new SyncedLearnerTracker();
    
    // 添加仲裁校验
    voteSet.addQuorumVerifier(self.getQuorumVerifier());
    
    //如果最后一题的提案配置不为空(如果没有配置并且没有配置动态文件则默认为空)并且版本大于当前版本，则加入仲裁校验中
    if (self.getLastSeenQuorumVerifier() != null
            && self.getLastSeenQuorumVerifier().getVersion() > self
                    .getQuorumVerifier().getVersion()) {
        voteSet.addQuorumVerifier(self.getLastSeenQuorumVerifier());
    }

    /*
     * First make the views consistent. Sometimes peers will have different
     * zxids for a server depending on timing.
     * 确认投票信息
     */
    for (Map.Entry<Long, Vote> entry : votes.entrySet()) {
        // 如果投票节点和当前的节点信息相等，则加入投票已确认队列
        if (vote.equals(entry.getValue())) {
            voteSet.addAck(entry.getKey());
        }
    }

    // 返回当前以及确认的投票节点数是否已经大于半数
    return voteSet.hasAllQuorums();
}
```

确认首节点是已经投过票和确认过的，为了避免在一个节点上反复进行选举

```$xslt

private boolean checkLeader(
            HashMap<Long, Vote> votes,
            long leader,
            long electionEpoch){

    boolean predicate = true;

    /*
     * If everyone else thinks I'm the leader, I must be the leader.
     * The other two checks are just for the case in which I'm not the
     * leader. If I'm not the leader and I haven't received a message
     * from leader stating that it is leading, then predicate is false.
     */
    if(leader != self.getId()){ // 首节点不是当前节点
        if(votes.get(leader) == null) predicate = false; // 当前的收到的投票信息还未生效 
        else if(votes.get(leader).getState() != ServerState.LEADING) predicate = false; // 投票的受节点还未更新状态
    } else if(logicalclock.get() != electionEpoch) { // 当前节点的逻辑时钟和首节点不等，即不是同一轮投票
        predicate = false;
    }

    return predicate;
}
```

#### 3.2.2 其他节点为观察者

观察者不参与投票，直接忽略。

#### 3.2.3 其他节点为跟随者

若是跟随者发送的投票信息，则说明已经选出了首节点

#### 3.2.4 其他节点为首节点

若是首节点发送的通知

```$xslt

/*
 * Consider all notifications from the same epoch
 * together.
 */
 // 如果是同一轮次的选举，本节点开始参与选举时，其他节点已经选好首节点
 // 例1：有节点1、2、3、4、5；开始依次启动了1、2、3节点，那么理论上这是选举的首节点就是3，接下来启动节点4或5的时候就会进入这个状态
 // 启动节点4后，它首先会对其他节点广播自己为首节点，它自身状态为LOOKING，节点1、2、3在收到这个通知后会由Messenger#WorkerReceiver处理，
 // 在处理过程中发现已经有首节点了，它就会将当前的首节点信息回复给节点4。节点4在收到首节点信息后首先会放到recvset中。
 // 例2：有个follower节点挂掉以后重启，这个时候得到的epoch 应该也是相等，
if(n.electionEpoch == logicalclock.get()){ 

    // 将首节点信息放入recvset
    recvset.put(n.sid, new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch));
    // 决定投票信息是否有效
    if(termPredicate(recvset, new Vote(n.leader,
                    n.zxid, n.electionEpoch, n.peerEpoch, n.state))
                     // 这里有点不解，收到的投票信息是首节点发送过来的，那么这里返回的一定是false,初始outofelection为空
                    && checkLeader(outofelection, n.leader, n.electionEpoch)) {
        
        // 设置当前节点状态
        self.setPeerState((n.leader == self.getId()) ?
                ServerState.LEADING: learningState());

        Vote endVote = new Vote(n.leader, n.zxid, n.peerEpoch);
        // 清空投票信息列表
        leaveInstance(endVote);
        return endVote;
    }
}

/*
 * Before joining an established ensemble, verify that
 * a majority are following the same leader.
 * Only peer epoch is used to check that the votes come
 * from the same ensemble. This is because there is at
 * least one corner case in which the ensemble can be
 * created with inconsistent zxid and election epoch
 * info. However, given that only one ensemble can be
 * running at a single point in time and that each 
 * epoch is used only once, using only the epoch to 
 * compare the votes is sufficient.
 * 
 * @see https://issues.apache.org/jira/browse/ZOOKEEPER-1732
 */
 
 // 当上面的节点执行完后，会将首节点的信息放入到outofelection
outofelection.put(n.sid, new Vote(n.leader, 
        IGNOREVALUE, IGNOREVALUE, n.peerEpoch, n.state));
if (termPredicate(outofelection, new Vote(n.leader,
        IGNOREVALUE, IGNOREVALUE, n.peerEpoch, n.state))
        && checkLeader(outofelection, n.leader, IGNOREVALUE)) { // 到这里两个条件都为true
    synchronized(this){
        
        // 将当前节点的逻辑时钟更新为首节点的epoch
        logicalclock.set(n.electionEpoch);
        // 设置当前节点类型
        self.setPeerState((n.leader == self.getId()) ?
                ServerState.LEADING: learningState());
    }
    
    Vote endVote = new Vote(n.leader, n.zxid, n.peerEpoch);
    // 清空收到的投票信息队列，并返回首节点的信息
    leaveInstance(endVote);
    return endVote;
}
```

至此，ZK的选举算法算是分析完了。

## 4. Messenger 类分析

### 4.1 Messenger#WorkerReceiver 选举信息接收类

这个类的主要作用就是接收选举过程中各个节点发送的投票信息。下面简要分析下这个类的主要功能。

WorkerReceiver主要有两个属性：stop(停止标志) 和 QuorumCnxManager(主要用于管理选举过程中的连接)。由于它继承ZooKeeperThread，所以我们主要看下它的run方法实现。

#### 4.1.1 从manager#recvQueue获取消息

```$xslt
// 首先从manager#recvQueue 接受消息队列中获取消息，如果没有则阻塞
    response = manager.pollRecvQueue(3000, TimeUnit.MILLISECONDS);
    if(response == null) continue;
    
    ...
    
    // 上面是一些兼容性的判断，然后将读取的值解析为投票信息对应的数据，如下：
    int rstate = response.buffer.getInt();
    long rleader = response.buffer.getLong();
    long rzxid = response.buffer.getLong();
    long relectionEpoch = response.buffer.getLong();
    long rpeerepoch;
```

#### 4.1.2 处理动态配置

```$xslt
    // 如果是动态配置
    try {
        // 重新从动态配置文件中载入配置信息
        rqv = self.configFromString(new String(b));
        QuorumVerifier curQV = self.getQuorumVerifier();
        // 如果动态配置的版本大于当前节点的版本
        if (rqv.getVersion() > curQV.getVersion()) {
            LOG.info("{} Received version: {} my version: {}", self.getId(),
                    Long.toHexString(rqv.getVersion()),
                    Long.toHexString(self.getQuorumVerifier().getVersion()));
             
            // 如果当前节点的状态还是ServerState.LOOKING，
            if (self.getPeerState() == ServerState.LOOKING) {
                LOG.debug("Invoking processReconfig(), state: {}", self.getServerState());
                // 重新处理配置信息，具体看代码实现，这里不展开了
                self.processReconfig(rqv, null, null, false);
                // 如果动态配置信息不是当前的节点信息则重启选举
                if (!rqv.equals(curQV)) {
                    LOG.info("restarting leader election");
                    self.shuttingDownLE = true;
                    self.getElectionAlg().shutdown();
    
                    break;
                }
            } else {
                LOG.debug("Skip processReconfig(), state: {}", self.getServerState());
            }
        }
    }

```

#### 4.1.3 处理非投票节点信息

```$xslt
// 如果当前节点不是投票节点，则想当前节信息封装成投票信息发送，
// 在还未选出首节点的情况下，为什么要将当前节点信息发送给response.sid?
if(!validVoter(response.sid)) {
    Vote current = self.getCurrentVote();
    QuorumVerifier qv = self.getQuorumVerifier();
    ToSend notmsg = new ToSend(ToSend.mType.notification,
            current.getId(),
            current.getZxid(),
            logicalclock.get(),
            self.getPeerState(),
            response.sid,
            current.getPeerEpoch(),
            qv.toString().getBytes());

    sendqueue.offer(notmsg);
}    
```

#### 4.1.4 处理接受的信息

```$xslt
    
    // 将收的信息封装成 Notification
    n.leader = rleader;
    n.zxid = rzxid;
    n.electionEpoch = relectionEpoch;
    n.state = ackstate;
    n.sid = response.sid;
    n.peerEpoch = rpeerepoch;
    n.version = version;
    n.qv = rqv;
    
    //如果当前节点也是选举状态，那么将Notification 放入recvqueue队列
    if(self.getPeerState() == QuorumPeer.ServerState.LOOKING){
        recvqueue.offer(n);
    
        /*
         * Send a notification back if the peer that sent this
         * message is also looking and its logical clock is
         * lagging behind.
         */
         // 如果对方节点也是选举状态，那么如果n.electionEpoch < logicalclock 那么将当前节点作为投票节点继续发送给response.sid
        if((ackstate == QuorumPeer.ServerState.LOOKING)
                && (n.electionEpoch < logicalclock.get())){
            Vote v = getVote();
            QuorumVerifier qv = self.getQuorumVerifier();
            ToSend notmsg = new ToSend(ToSend.mType.notification,
                    v.getId(),
                    v.getZxid(),
                    logicalclock.get(),
                    self.getPeerState(),
                    response.sid,
                    v.getPeerEpoch(),
                    qv.toString().getBytes());
            sendqueue.offer(notmsg);
        }
    } else {
    
        /*
        * If this server is not looking, but the one that sent the ack
        * is looking, then send back what it believes to be the leader.
        */
        // 如果当前节点不是选举状态，那么则说明当前节点已经有一个确定的首节点信息，那么会将当前的首节点信息进行封装再发给response.sid节点
        Vote current = self.getCurrentVote();
        if(ackstate == QuorumPeer.ServerState.LOOKING){
             if(LOG.isDebugEnabled()){
                 LOG.debug("Sending new notification. My id ={} recipient={} zxid=0x{} leader={} config version = {}",
                         self.getId(),
                         response.sid,
                         Long.toHexString(current.getZxid()),
                         current.getId(),
                         Long.toHexString(self.getQuorumVerifier().getVersion()));
             }
            
             QuorumVerifier qv = self.getQuorumVerifier();
             ToSend notmsg = new ToSend(
                     ToSend.mType.notification,
                     current.getId(),
                     current.getZxid(),
                     current.getElectionEpoch(),
                     self.getPeerState(),
                     response.sid,
                     current.getPeerEpoch(),
                     qv.toString().getBytes());
             sendqueue.offer(notmsg);
        }
    }
    
```

### 4.2 Messenger#WorkerSender 选举信息接收类

同上，我们重点看发送类的run方法。

#### 4.2.1 方法实现

```$xslt

while (!stop) {
    try {
        // 从sendqueue队列取出要发送的消息，如果没有则阻塞等待
        ToSend m = sendqueue.poll(3000, TimeUnit.MILLISECONDS);
        if(m == null) continue;
        
        // 处理消息
        process(m);
    } catch (InterruptedException e) {
        break;
    }
}
```

#### 4.2.2 处理消息

```$xslt

// 这个方法主要是将ToSend -> ByteBuffer，然后调用manager#toSend发送消息
void process(ToSend m) {
    ByteBuffer requestBuffer = buildMsg(m.state.ordinal(),
                                        m.leader,
                                        m.zxid,
                                        m.electionEpoch,
                                        m.peerEpoch,
                                        m.configData);
    
    manager.toSend(m.sid, requestBuffer);

}
```

## 5. QuorumCnxManager 类实现分析
QuorumCnxManager主要是管理在选举过程中各节点之间的连接，我们主要分析这个类的Listener、RecvWorker和SendWorker几个子类的功能。

### 5.1 QuorumCnxManager#Listener

这个类是个zk的一个后台线程，会一直监听选举端口，接受选举信息，下面是主要代码分析：

```$xslt
while (!shutdown) {
    // 接受来自客户端的连接
    client = ss.accept();
    setSockOpts(client);
    LOG.info("Received connection request "
            + client.getRemoteSocketAddress());
    // Receive and handle the connection request
    // asynchronously if the quorum sasl authentication is
    // enabled. This is required because sasl server
    // authentication process may take few seconds to finish,
    // this may delay next peer connection requests.
    if (quorumSaslAuthEnabled) {
        receiveConnectionAsync(client);
    } else {
        // 处理连接，由于 receiveConnection -> handleConnection
        // 所以我们下面只看 handleConnection 方法实现
        receiveConnection(client);
    }
    numRetries = 0;
}
```

```$xslt

private void handleConnection(Socket sock, DataInputStream din){

    ...
    // 省略的部分主要是读取数据，并且解析出对应sid 、protocolVersion、electionAddr等信息
    
    // 如果对方节点的sid 小于当前节点id，则关闭当前的连接，主动打开一个到sid的连接
    // 即只允许sid 大的到 sid小的连接，不允许sid 小的连接sid 大的节点
    if (sid < self.getId()) {
        /*
         * This replica might still believe that the connection to sid is
         * up, so we have to shut down the workers before trying to open a
         * new connection.
         */
        SendWorker sw = senderWorkerMap.get(sid);
        if (sw != null) {
            sw.finish();
        }
    
        /*
         * Now we start a new connection
         */
        LOG.debug("Create new connection to server: {}", sid);
        closeSocket(sock);
    
        if (electionAddr != null) {
            connectOne(sid, electionAddr);
        } else {
            connectOne(sid);
        }
    
    } else { // Otherwise start worker threads to receive data.
        
        // 启动 SendWorker 、 RecvWorker
        SendWorker sw = new SendWorker(sock, sid);
        RecvWorker rw = new RecvWorker(sock, din, sid, sw);
        sw.setRecv(rw);
    
        SendWorker vsw = senderWorkerMap.get(sid);
    
        if (vsw != null) {
            vsw.finish();
        }
    
        // 为每个sid都开启一个 SendWorker 、RecvWorker
        senderWorkerMap.put(sid, sw);
        
        // 将发送消息放入 sid 对应的阻塞队列中
        queueSendMap.putIfAbsent(sid,
                new ArrayBlockingQueue<ByteBuffer>(SEND_CAPACITY));
    
        sw.start();
        rw.start();
    }


}

```

### 5.2 QuorumCnxManager#SendWorker

消息发送类，对于这个我们还是主要看它的run方法实现。

```$xslt

// 在线程启动时，首先会获取sid对应的发送队列，如果发送队列为空，则尝试发送最后一次发送的消息，以确保最后发送的消息会被每个节点收到。
// 在等待自己活着对方节点读取或处理最后一条消息之前断开连接，那么则可以删除消息。对方节点可以正确处理重复消息。
ArrayBlockingQueue<ByteBuffer> bq = queueSendMap.get(sid);
if (bq == null || isSendQueueEmpty(bq)) {
   ByteBuffer b = lastMessageSent.get(sid);
   if (b != null) {
       LOG.debug("Attempting to send lastMessage to sid=" + sid);
       send(b);
   }
}


// 处理发送队列中的消息
while (running && !shutdown && sock != null) {

    ByteBuffer b = null;
    try {
        
        // 获取sid对应的发送队列
        ArrayBlockingQueue<ByteBuffer> bq = queueSendMap
                .get(sid);
        if (bq != null) {
            
            // 获取发送队列中的消息，由election 调用 toSend 方法将发送的消息添加到当前队列中
            b = pollSendQueue(bq, 1000, TimeUnit.MILLISECONDS);
        } else {
            LOG.error("No queue of incoming messages for " +
                      "server " + sid);
            break;
        }

        //  发送消息给sid
        if(b != null){
            lastMessageSent.put(sid, b);
            send(b);
        }
    } catch (InterruptedException e) {
        LOG.warn("Interrupted while waiting for message on queue",
                e);
    }
}
```

### 5.3 QuorumCnxManager#RecvWorker

消息接受类

```$xslt

while (running && !shutdown && sock != null) {
    /**
     * Reads the first int to determine the length of the
     * message
     */
    int length = din.readInt();
    if (length <= 0 || length > PACKETMAXSIZE) {
        throw new IOException(
                "Received packet with invalid packet: "
                        + length);
    }
    /**
     * Allocates a new ByteBuffer to receive the message
     */
    byte[] msgArray = new byte[length];
    din.readFully(msgArray, 0, length);
    // 将读取到的消息封装成 ByteBuffer
    ByteBuffer message = ByteBuffer.wrap(msgArray);
    
    // 将message -> Message 添加到 recvQueue 队列中，后election 会调用 QuorumCnxManager#pollRecvQueue 获取消息
    addToRecvQueue(new Message(message.duplicate(), sid));
}
```




