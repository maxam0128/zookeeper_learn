# Leader 初始化

当节点的状态为 LEADING 时，它就会当选为当前集群的一个leader，但是这个时候它还不是真正的leader，只有它行使一些leader的权利（lead的部分流程）成功以后，才会真正的成为leader，
下面就逐步分写它的lead过程代码如下：

```
case LEADING:
LOG.info("LEADING");
try {
    
    // 构建Leader对象
    setLeader(makeLeader(logFactory));
    
    // Leader#lead，在没有异常的情况下线程在这里阻塞
    leader.lead();
    setLeader(null);
} catch (Exception e) {
    LOG.warn("Unexpected exception",e);
} finally {
    if (leader != null) {
        leader.shutdown("Forcing shutdown");
        setLeader(null);
    }
    updateServerState();
}
break;
```

## Leader#lead

下面看lead的主要流程：

- 1、恢复数据和session
- 2、启动 LearnerCnxAcceptor ，接受来自Follower的连接，并为每一个follower启动一个LearnerHandler 来同步数据
- 3、这里阻塞，直到大多数的follower和leader建立连接，然后根据每个Follower的zxid和leader的epoch 计算出最新的epoch
- 4、根据new epoch 计算最新的zxid
- 5、设置 last proposed quorum verifier 
- 6、阻塞等待大多数follower 对于NEWLEADER 数据包的ACK回复
- 7、启动 LeaderZooKeeperServer 
- 8、设置管理服务
- 9、定时给follower发送ping数据包，确认follower还活着
- 10、如果在两个周期内没有收到大多数的follower的确认信息，则会关闭的Leader服务

下面我们逐步看以上的步骤。


### 1、ZookeeperServer#loadData

作为leader的第一步首先就是从磁盘恢复数据，这些数据后面是要同步给follower的。
通过zk的选举分析可知，zk在选举阶段已经load过一次数据，所以只有在zk server没有被初始化才会加载数据(在本地数据比较多的情况下这个还是很重要的)，
下面代码为具体实现：

```
public void loadData() throws IOException, InterruptedException {
    
    // zkdb 是否已被初始化
    if(zkDb.isInitialized()){
        setZxid(zkDb.getDataTreeLastProcessedZxid());
    }
    else {
        setZxid(zkDb.loadDataBase());
    }
    
    // 删除已经超时的session
    LinkedList<Long> deadSessions = new LinkedList<Long>();
    for (Long session : zkDb.getSessions()) {
        if (zkDb.getSessionWithTimeOuts().get(session) == null) {
            deadSessions.add(session);
        }
    }

    for (long session : deadSessions) {
        
        // 删除session
        // 1、ZKDatabase#killSession -> DataTree#killSession 会删除当前session下的所有临时节点
        // 2、LeaderSessionTracker#removeSession : 会删除本地session和global session
        killSession(session, zkDb.getDataTreeLastProcessedZxid());
    }

    // 保存snapshot
    takeSnapshot();
}
```

### 2、启动 LearnerCnxAcceptor

这个类主要是接受来自follower的连接，并且为每一个follower都新建一个LearnerHandler 处理它们之间的连接，可以看做是leader 和 follower之间的一个连接管理类。核心代码如下：

```
// 新建一个连接，接受来自follower的连接请求
Socket s = ss.accept();
// start with the initLimit, once the ack is processed
// in LearnerHandler switch to the syncLimit
// 设置follower的数据同步超时时间
s.setSoTimeout(self.tickTime * self.initLimit);
s.setTcpNoDelay(nodelay);

BufferedInputStream is = new BufferedInputStream(
        s.getInputStream());

// 为每一个follower 新建一个LearnerHandler
LearnerHandler fh = new LearnerHandler(s, is, Leader.this);
fh.start();
```

#### LearnerHandler- follower和leader 之间的通信类

LearnerHandler为follower和leader的通信类，实现相对比较复杂，下面我们分步来看这个类的具体实现。

##### 1、读取follower 数据

```

// 将建立的 LearnerHandler 加入到Learner列表中
leader.addLearnerHandler(this);

// 两个ack之间的时间间隔，如果超过这个时间则说明follower断开
tickOfNextAckDeadline = leader.self.tick.get()
        + leader.self.initLimit + leader.self.syncLimit;

ia = BinaryInputArchive.getArchive(bufferedInput);
bufferedOutput = new BufferedOutputStream(sock.getOutputStream());
oa = BinaryOutputArchive.getArchive(bufferedOutput);

QuorumPacket qp = new QuorumPacket();
ia.readRecord(qp, "packet");

// follower 连上leader 后，会首先将自身的信息(Leader.FOLLOWERINFO)发送给leader
// follower 首先通过 Learner#registerWithLeader 发送消息给leader
if(qp.getType() != Leader.FOLLOWERINFO && qp.getType() != Leader.OBSERVERINFO){
    LOG.error("First packet " + qp.toString()
            + " is not FOLLOWERINFO or OBSERVERINFO!");
    return;
}


```

##### 2、解析follower 数据，并确定选举周期

```

byte learnerInfoData[] = qp.getData();

// 读取Follower 发送的data数据，从follower 可知，此data是一个 LearnerInfo 对象，包括serverid、protocolVersion、configVersion信息
if (learnerInfoData != null) {
    
    // 读取 LearnerInfo 中的数据，具体可参考 LearnerInfo
    ByteBuffer bbsid = ByteBuffer.wrap(learnerInfoData);
    if (learnerInfoData.length >= 8) {
        this.sid = bbsid.getLong();
    }
    if (learnerInfoData.length >= 12) {
        this.version = bbsid.getInt(); // protocolVersion
    }
    if (learnerInfoData.length >= 20) {
        long configVersion = bbsid.getLong();
        if (configVersion > leader.self.getQuorumVerifier().getVersion()) {
            throw new IOException("Follower is ahead of the leader (has a later activated configuration)");
        }
    }
} else {
    
    // 如果follower没有发送自身的sid，leader会先给它一个值，所有follower的个数+1
    this.sid = leader.followerCounter.getAndDecrement();
}
```

##### 3、确定follower的选举周期

```
// 确定连接节点类型
if (qp.getType() == Leader.OBSERVERINFO) {
      learnerType = LearnerType.OBSERVER;
}

// 获取选举周期 epoch
long lastAcceptedEpoch = ZxidUtils.getEpochFromZxid(qp.getZxid());

long peerLastZxid;
StateSummary ss = null;
long zxid = qp.getZxid();

// 确定新的选举周期
// leader 会阻塞等待收到大多数follower发送的信息之后，然后根据follower发送的选举周期来确定本轮的选举周期(所有follower最大的选举周期+1)
// 在Leader#getEpochToPropose 实现中，可以看出当前集群对于大多数的节点定义是不包括 observer 的，具体实现参考Leader#getEpochToPropose
long newEpoch = leader.getEpochToPropose(this.getSid(), lastAcceptedEpoch);
// 计算新的 zxid
long newLeaderZxid = ZxidUtils.makeZxid(newEpoch, 0);
```
##### 4、leader 广播最新的 newLeaderZxid

leader 将计算出来新的zxid 广播给follower之后，再阻塞等待大多数follower的ack消息。
```

// follower 发送默认版本信息为 0x10000 ，所以直接看 else分支。
if (this.getVersion() < 0x10000) {
    // we are going to have to extrapolate the epoch information
    long epoch = ZxidUtils.getEpochFromZxid(zxid);
    ss = new StateSummary(epoch, zxid);
    // fake the message
    leader.waitForEpochAck(this.getSid(), ss);
} else {
    byte ver[] = new byte[4];
    ByteBuffer.wrap(ver).putInt(0x10000);
    
    // 将newLeaderZxid 封装成Leader.LEADERINFO后，广播给所有Learner
    QuorumPacket newEpochPacket = new QuorumPacket(Leader.LEADERINFO, newLeaderZxid, ver, null);
    oa.writeRecord(newEpochPacket, "packet");
    bufferedOutput.flush();
    QuorumPacket ackEpochPacket = new QuorumPacket();
    ia.readRecord(ackEpochPacket, "packet");
    if (ackEpochPacket.getType() != Leader.ACKEPOCH) {
        LOG.error(ackEpochPacket.toString()
                + " is not ACKEPOCH");
        return;
    }
    ByteBuffer bbepoch = ByteBuffer.wrap(ackEpochPacket.getData());
    
    // 读取 follower 回复的 epoch 信息，如果和leader 相等则为-1，否则为 follower 旧的 epoch信息
    ss = new StateSummary(bbepoch.getInt(), ackEpochPacket.getZxid());
    
    // 以阻塞的方式等待大多数的follower回复ack消息
    leader.waitForEpochAck(this.getSid(), ss);
}

// follower 回传ack消息时，会将自身的lastLogZxid回复给leader，leader会依据这个值判断follower使用什么策略同步数据
peerLastZxid = ss.getLastZxid();
```
##### 5、和follower同步数据

```
public boolean syncFollower(long peerLastZxid, ZKDatabase db, Leader leader) {

    // 是否是集群初始化的选举
    boolean isPeerNewEpochZxid = (peerLastZxid & 0xffffffffL) == 0;
    // Keep track of the latest zxid which already queued
    
    // follower 的 maxZxid
    long currentZxid = peerLastZxid;
    
    // follower是否需要同步leader的snapshot
    boolean needSnap = true;
    
    // 是否同步 txnLog
    boolean txnLogSyncEnabled = db.isTxnLogSyncEnabled();
    ReentrantReadWriteLock lock = db.getLogLock();
    ReadLock rl = lock.readLock();
    try {
        rl.lock();
        
        // leader 的 max、min log
        long maxCommittedLog = db.getmaxCommittedLog();
        long minCommittedLog = db.getminCommittedLog();
        long lastProcessedZxid = db.getDataTreeLastProcessedZxid();

        // leader 的提交日志
        if (db.getCommittedLog().isEmpty()) {

            minCommittedLog = lastProcessedZxid;
            maxCommittedLog = lastProcessedZxid;
        }

        // 计算follower的同步策略:处理的5种方式
        // 1、强制发送snapshot(测试目的)
        // 2、follower 和 leader 已经同步，则发送空的 diff 消息
        // 3、follower 的 txn 比leader 要多，那么则发送 TRUNC ，回滚follower多余的 txn数据。
        // 4、follower 在committedLog同步的范围内，那么根据follower 的zxid来决定发送 TRUNC 还是DIFF,如果follower 在同步中的话就发送 空的DIFF
        // 5、follower 丢失了committedLog。会将leader 磁盘上的txnLog和committedLog同步给follower，如果失败了，会发送snapshot
        
        if (forceSnapSync) {
            // 强制同步leader的snapshot，在生产环境下，由于数据可能会比较多，所以一般不会配置该项
            LOG.warn("Forcing snapshot sync - should not see this in production");
        } else if (lastProcessedZxid == peerLastZxid) {
            
            // 如果 follower 的peerLastZxid 和 leader lastProcessedZxid相等，说明两个节点的数据一致，不需要进行同步，这时只需要给follower 发送一个diff的包
            queueOpPacket(Leader.DIFF, peerLastZxid);
            needOpPacket = false;
            needSnap = false;
        } else if (peerLastZxid > maxCommittedLog && !isPeerNewEpochZxid) {
            
            // follower的数据比leader 超前，则回滚follower的数据到leader#maxCommittedLog
            queueOpPacket(Leader.TRUNC, maxCommittedLog);
            currentZxid = maxCommittedLog;
            needOpPacket = false;
            needSnap = false;
        } else if ((maxCommittedLog >= peerLastZxid)
                && (minCommittedLog <= peerLastZxid)) {
            // follower 数据在处于leader 的 最大最小事务之间，则增量同步 
            Iterator<Proposal> itr = db.getCommittedLog().iterator();
            
            // 这里有两种情形：
            // 1、发送 DIFF 给 follower : 对应 follower zxid 在leader的history中
            // 2、发送 TRUN + DIFF 给 follower ： 对应 follower zxid 满足上述条件，但是 not in leader的 commit proposal中，所以先发送 TRUN 包，然后在发送DIFF 包
            currentZxid = queueCommittedProposals(itr, peerLastZxid,
                                                 null, maxCommittedLog);
            needSnap = false;
        } else if (peerLastZxid < minCommittedLog && txnLogSyncEnabled) {
            // follower 最大的zxid 小于 leader 最小 minCommittedLog，并且允许从txnLog中同步数据

            // 计算事务日志允许同步的大小
            long sizeLimit = db.calculateTxnLogSizeLimit();
            
            // 如果follower的zxid 在 leader的事务日志中，那么只需要同步事务日志中差异的部分，不需要同步整个snapshot，否则就需要同步snapshot
            Iterator<Proposal> txnLogItr = db.getProposalsFromTxnLog(
                    peerLastZxid, sizeLimit);
            if (txnLogItr.hasNext()) {
                LOG.info("Use txnlog and committedLog for peer sid: " +  getSid());
                currentZxid = queueCommittedProposals(txnLogItr, peerLastZxid,
                                                     minCommittedLog, maxCommittedLog);

                LOG.debug("Queueing committedLog 0x" + Long.toHexString(currentZxid));
                Iterator<Proposal> committedLogItr = db.getCommittedLog().iterator();
                currentZxid = queueCommittedProposals(committedLogItr, currentZxid,
                                                     null, maxCommittedLog);
                needSnap = false;
            }
            // closing the resources
            if (txnLogItr instanceof TxnLogProposalIterator) {
                TxnLogProposalIterator txnProposalItr = (TxnLogProposalIterator) txnLogItr;
                txnProposalItr.close();
            }
        } else {
            LOG.warn("Unhandled scenario for peer sid: " +  getSid());
        }
        
        // 对这个方法的理解是，follower在故障恢复时，如果和leader的数据同步完成，还要再次检查同步数据这段时间是不是leader有了新的提交，需要再次同步
        leaderLastZxid = leader.startForwarding(this, currentZxid);
    } finally {
        rl.unlock();
    }

    if (needOpPacket && !needSnap) {
        // This should never happen, but we should fall back to sending
        // snapshot just in case.
        LOG.error("Unhandled scenario for peer sid: " +  getSid() +
                 " fall back to use snapshot");
        needSnap = true;
    }
    
    // 是否同步snapshot
    return needSnap;
}
```

##### 6、回复

```
// version == 0x10000 ，else分支
if (getVersion() < 0x10000) {
    QuorumPacket newLeaderQP = new QuorumPacket(Leader.NEWLEADER,
            newLeaderZxid, null, null);
    oa.writeRecord(newLeaderQP, "packet");
} else {

    // 回复Leader.NEWLEADER 信息给follower
    QuorumPacket newLeaderQP = new QuorumPacket(Leader.NEWLEADER,
            newLeaderZxid, leader.self.getLastSeenQuorumVerifier()
                    .toString().getBytes(), null);
    queuedPackets.add(newLeaderQP);
}
bufferedOutput.flush();
```

##### 7、同步snapshot 

```
/* if we are not truncating or sending a diff just send a snapshot */
// 在前面已经分析过leader 在什么情形下同步snapshot给follower，这里就是snapshot的同步过程
if (needSnap) {
    boolean exemptFromThrottle = getLearnerType() != LearnerType.OBSERVER;
    
    LearnerSnapshot snapshot = 
            leader.getLearnerSnapshotThrottler().beginSnapshot(exemptFromThrottle);
    try {
        long zxidToSend = leader.zk.getZKDatabase().getDataTreeLastProcessedZxid();
        
        // 同步前先发送 Leader.SNAP 数据包
        oa.writeRecord(new QuorumPacket(Leader.SNAP, zxidToSend, null, null), "packet");
        bufferedOutput.flush();

        // 导出leader 数据
        leader.zk.getZKDatabase().serializeSnapshot(oa);
        
        // 最后签名
        oa.writeString("BenWasHere", "signature");
        bufferedOutput.flush();
    } finally {
        snapshot.close();
    }
}
```

##### 8、启动同步线程

以上的数据，除了snapshot是直接通过sock发送给follower，其他的数据包都是先添加到queuedPackets队列中，这里启动的线程异步发送队列中的数据包

```
protected void startSendingPackets() {
    if (!sendingThreadStarted) {
        // Start sending packets
        new Thread() {
            public void run() {
                Thread.currentThread().setName(
                        "Sender-" + sock.getRemoteSocketAddress());
                try {
                    
                    // 发送队列中的数据包，如果收到proposalOfDeath数据包(稍后我们分析zk的优雅停机)或数据同步发生异常，会结束当前数据同步
                    sendPackets();
                } catch (InterruptedException e) {
                    LOG.warn("Unexpected interruption " + e.getMessage());
                }
            }
        }.start();
        sendingThreadStarted = true;
    } else {
        LOG.error("Attempting to start sending thread after it already started");
    }
}
```

##### 9、等待大多数follower发送ack消息

leader.waitForNewLeaderAck(getSid(), qp.getZxid());
```

public void waitForNewLeaderAck(long sid, long zxid)
        throws InterruptedException {

    synchronized (newLeaderProposal.qvAcksetPairs) {

        newLeaderProposal.addAck(sid);
        
        // 可以看出，如果leader 没有收到大多数follower的ack消息后，当前的同步线程会一直阻塞在这个地方，直到超时，代码如下。
        if (newLeaderProposal.hasAllQuorums()) {
            quorumFormed = true;
            newLeaderProposal.qvAcksetPairs.notifyAll();
        } else {
            long start = Time.currentElapsedTime();
            long cur = start;
            long end = start + self.getInitLimit() * self.getTickTime();
            
            // 等待超时或者leader已经收到大多数follower发送的ack消息
            while (!quorumFormed && cur < end) {
                newLeaderProposal.qvAcksetPairs.wait(end - cur);
                cur = Time.currentElapsedTime();
            }
        }
    }
}
```
##### 10、等待leader 服务启动完成

```
synchronized(leader.zk){
    while(!leader.zk.isRunning() && !this.isInterrupted()){
        leader.zk.wait(20);
    }
}
```

##### 11、发送 UPTODATE 给follower，数据同步完成

follower 在收到Leader.UPTODATE消息后，退出同步数据逻辑，然后启动服务，开始对外服务，接受来自客户端的请求。

```
LOG.debug("Sending UPTODATE message to " + sid);      
queuedPackets.add(new QuorumPacket(Leader.UPTODATE, -1, null, null));
```
##### 12、处理来自follower的消息

到这一步，leader和follower之间的数据同步就已经完成了，leader 也可以处理来自该follower的请求。

### 3、阻塞同步选举周期

参考下面代码实现(getEpochToPropose)：

```
public long getEpochToPropose(long sid, long lastAcceptedEpoch) throws InterruptedException, IOException {
    synchronized(connectingFollowers) {
        if (!waitingForNewEpoch) {
            return epoch;
        }
        
        // 如果follower 的epoch 大于当前的 epoch，则自增后为当前的epoch
        if (lastAcceptedEpoch >= epoch) {
            epoch = lastAcceptedEpoch+1;
        }
        
        // 忽略observer
        if (isParticipant(sid)) {
            connectingFollowers.add(sid);
        }
        
        // 如果已经得到大多的follower的确认，唤醒其他的等待线程并跳出阻塞代码
        QuorumVerifier verifier = self.getQuorumVerifier();
        if (connectingFollowers.contains(self.getId()) &&
                                        verifier.containsQuorum(connectingFollowers)) {
            // 是否等待 newEpoch 的标志位
            waitingForNewEpoch = false;
            self.setAcceptedEpoch(epoch);
            connectingFollowers.notifyAll();
        } else {
            long start = Time.currentElapsedTime();
            long cur = start;
            long end = start + self.getInitLimit()*self.getTickTime();
            
            // 阻塞等待
            while(waitingForNewEpoch && cur < end) {
                connectingFollowers.wait(end - cur);
                cur = Time.currentElapsedTime();
            }
            
            // 如果超时，则退出，重新进入选举过程，将当前节点的状态重新置为LOOKING
            if (waitingForNewEpoch) {
                throw new InterruptedException("Timeout while waiting for epoch from quorum");
            }
        }
        return epoch;
    }
}
```

### 4、// todo 吧





 











