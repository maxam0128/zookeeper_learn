
# Follower

zk在选举完成后，如果当前节点成为follower角色之后，它要做些什么？下面我们从源码的角度看看follower在选举完成后的主要工作。
我们知道zk集群在选举阶段它的状态为：LOOKING，在这个状态下会通过Election#lookForLeader(默认为FastLeaderElection#lookForLeader)来进行选举，选出Leader之后会更新自身节点的角色状态。
下面我们着重看下FOLLOWING状态，代码片段如下：


```
case FOLLOWING:
    try {
        LOG.info("FOLLOWING");
        
        // 初始化follower，logFactory 为zk写snapshot 和 txnLog的一个辅助类
        setFollower(makeFollower(logFactory));
        // 1、connect to leader 
        // 2、同步数据
        follower.followLeader();
    } catch (Exception e) {
       LOG.warn("Unexpected exception",e);
    } finally {
       follower.shutdown();
       setFollower(null);
       updateServerState();
    }
    break;
```

对于初始化过程比较简单，这里先不做介绍了，下面我们着重看下follower#followLeader的过程。

## Follower#followLeader

followLeader的过程主要会执行一下操作：

- 1、找出 leader 并和leader建立连接
- 2、和 leader 同步选举轮次
- 3、根据选举轮次向leader同步数据
- 4、数据同步完成后开始处理来自leader的消息

下面我们分别来看这5步的实现。

### 1、find and connect to leader

这部分相对比较简单，根据sid找出leader，获取leader的连接信息，然后和leader建立连接。如果连接过程中出现异常，那么会在限制的时间内重试5次。下面简单的看下这两个步骤。

#### 1.1、查找leader

```
protected QuorumServer findLeader() {
    QuorumServer leaderServer = null;
    
    // leader产生后，会设置当前的投票信息
    Vote current = self.getCurrentVote();
    
    // 从服务节点视图中查找当前投票sid对应的节点，即为leader
    for (QuorumServer s : self.getView().values()) {
        if (s.id == current.getId()) {
            
            // 在尝试连接前确保leader的连接信息是准确的
            s.recreateSocketAddresses();
            leaderServer = s;
            break;
        }
    }
    if (leaderServer == null) {
        LOG.warn("Couldn't find the leader with id = "
                + current.getId());
    }
    return leaderServer;
}
```

#### 1.2、连接leader节点

```
protected void connectToLeader(InetSocketAddress addr, String hostname)
    throws IOException, ConnectException, InterruptedException {
    sock = new Socket();
    // self.tickTime 间隔时间
    // self.initLimit 初始同步阶段重试的次数         
    sock.setSoTimeout(self.tickTime * self.initLimit);

    // 超时时间
    int initLimitTime = self.tickTime * self.initLimit;
    
    // 剩余的超时时间
    int remainingInitLimitTime = initLimitTime;
    long startNanoTime = nanoTime();
    
    // 这里可以看到在remainingInitLimitTime > 1000(1s) 或者 tries < 5 的时候，在连接过程产生IOException 异常会进行重试
    for (int tries = 0; tries < 5; tries++) {
        try {
            remainingInitLimitTime = initLimitTime - (int)((nanoTime() - startNanoTime) / 1000000);
            if (remainingInitLimitTime <= 0) {
                LOG.error("initLimit exceeded on retries.");
                throw new IOException("initLimit exceeded on retries.");
            }
            sockConnect(sock, addr, Math.min(self.tickTime * self.syncLimit, remainingInitLimitTime));
            sock.setTcpNoDelay(nodelay);
            break;
        } catch (IOException e) {
            remainingInitLimitTime = initLimitTime - (int)((nanoTime() - startNanoTime) / 1000000);

            if (remainingInitLimitTime <= 1000) {
                LOG.error("Unexpected exception, initLimit exceeded. tries=" + tries +
                         ", remaining init limit=" + remainingInitLimitTime +
                         ", connecting to " + addr,e);
                throw e;
            } else if (tries >= 4) {
                LOG.error("Unexpected exception, retries exceeded. tries=" + tries +
                         ", remaining init limit=" + remainingInitLimitTime +
                         ", connecting to " + addr,e);
                throw e;
            } else {
                LOG.warn("Unexpected exception, tries=" + tries +
                        ", remaining init limit=" + remainingInitLimitTime +
                        ", connecting to " + addr,e);
                sock = new Socket();
                sock.setSoTimeout(self.tickTime * self.initLimit);
            }
        }
        Thread.sleep(1000);
    }
    self.authLearner.authenticate(sock, hostname);
    leaderIs = BinaryInputArchive.getArchive(new BufferedInputStream(
            sock.getInputStream()));
    bufferedOutput = new BufferedOutputStream(sock.getOutputStream());
    leaderOs = BinaryOutputArchive.getArchive(bufferedOutput);
}
```

### 2、与Leader 同步选举轮次

这个主要是通过Learner#registerWithLeader 实现。通过Learner实现 follower 和 observer 的代码复用。这个方法主要有以下功能：

- 1、follower 会将自身的zxid

```
protected long registerWithLeader(int pktType) throws IOException{

    long lastLoggedZxid = self.getLastLoggedZxid();
    QuorumPacket qp = new QuorumPacket();                
    qp.setType(pktType);
    qp.setZxid(ZxidUtils.makeZxid(self.getAcceptedEpoch(), 0));
    
    /*
     * Add sid to payload
     */
    LearnerInfo li = new LearnerInfo(self.getId(), 0x10000, self.getQuorumVerifier().getVersion());
    ByteArrayOutputStream bsid = new ByteArrayOutputStream();
    BinaryOutputArchive boa = BinaryOutputArchive.getArchive(bsid);
    boa.writeRecord(li, "LearnerInfo");
    qp.setData(bsid.toByteArray());
    
    writePacket(qp, true);
    readPacket(qp);        
    final long newEpoch = ZxidUtils.getEpochFromZxid(qp.getZxid());
    if (qp.getType() == Leader.LEADERINFO) {
        // we are connected to a 1.0 server so accept the new epoch and read the next packet
        leaderProtocolVersion = ByteBuffer.wrap(qp.getData()).getInt();
        byte epochBytes[] = new byte[4];
        final ByteBuffer wrappedEpochBytes = ByteBuffer.wrap(epochBytes);
        if (newEpoch > self.getAcceptedEpoch()) {
            wrappedEpochBytes.putInt((int)self.getCurrentEpoch());
            self.setAcceptedEpoch(newEpoch);
        } else if (newEpoch == self.getAcceptedEpoch()) {
            // since we have already acked an epoch equal to the leaders, we cannot ack
            // again, but we still need to send our lastZxid to the leader so that we can
            // sync with it if it does assume leadership of the epoch.
            // the -1 indicates that this reply should not count as an ack for the new epoch
            wrappedEpochBytes.putInt(-1);
        } else {
            throw new IOException("Leaders epoch, " + newEpoch + " is less than accepted epoch, " + self.getAcceptedEpoch());
        }
        QuorumPacket ackNewEpoch = new QuorumPacket(Leader.ACKEPOCH, lastLoggedZxid, epochBytes, null);
        writePacket(ackNewEpoch, true);
        return ZxidUtils.makeZxid(newEpoch, 0);
    } else {
        if (newEpoch > self.getAcceptedEpoch()) {
            self.setAcceptedEpoch(newEpoch);
        }
        if (qp.getType() != Leader.NEWLEADER) {
            LOG.error("First packet should have been NEWLEADER");
            throw new IOException("First packet should have been NEWLEADER");
        }
        return qp.getZxid();
    }
} 
```










