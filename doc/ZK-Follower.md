
# Follower 初始化

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

- 1、follower 会将自身的信息发送给leader
- 2、从leader 返回的消息解析出最新的选举轮次-newEpoch
- 3、将自身的lastLoggedZxid消息封装成ACK消息包回传给leader

下面我们分别看这三点的实现。

#### 2.1、发送自身信息

follower 会将自身的信息封装成FOLLOWERINFO数据包，然后发送给leader，代码如下：

```
long lastLoggedZxid = self.getLastLoggedZxid();
QuorumPacket qp = new QuorumPacket();

// pktType 为 Leader.FOLLOWERINFO                
qp.setType(pktType);

// follower接受的选举轮次
qp.setZxid(ZxidUtils.makeZxid(self.getAcceptedEpoch(), 0));

构造 QuorumPacket 中的data数据，包含follower的sid，protocolVersion，configVersion
LearnerInfo li = new LearnerInfo(self.getId(), 0x10000, self.getQuorumVerifier().getVersion());
ByteArrayOutputStream bsid = new ByteArrayOutputStream();
BinaryOutputArchive boa = BinaryOutputArchive.getArchive(bsid);
boa.writeRecord(li, "LearnerInfo");
qp.setData(bsid.toByteArray());

// 发送数据
writePacket(qp, true);
```

#### 2.2、解析leader返回的数据

```

// 从leader 的回传数据解析出最新的选举轮次 epoch ，
// 这个时候leader 一定是接受到了大多数的 Follower 连接，并对每个 follower的epoch进行了处理，计算出最新的epoch        
final long newEpoch = ZxidUtils.getEpochFromZxid(qp.getZxid());

```

#### 2.3、封装lastLoggedZxid，返回ACK给leader

leader在收到大多数follower发送的 FOLLOWERINFO 请求后，会确定一个最新的选举轮次，并将自身的信息封装成Leader.LEADERINFO数据包回传给每个follower。代码如下：

```
leaderProtocolVersion = ByteBuffer.wrap(qp.getData()).getInt();
byte epochBytes[] = new byte[4];
final ByteBuffer wrappedEpochBytes = ByteBuffer.wrap(epochBytes);
   .....

// 将follower的lastLoggedZxid封装成一个ACK消息包回传给leader，
// leader 在收到ack消息后会使用follower 回传的 lastLoggedZxid 来确认此follower向leader同步数据的方式，例如Leader.SNAP、DIFF、TRUNC
QuorumPacket ackNewEpoch = new QuorumPacket(Leader.ACKEPOCH, lastLoggedZxid, epochBytes, null);
writePacket(ackNewEpoch, true);

// 用最新的选举轮次生成zxid
return ZxidUtils.makeZxid(newEpoch, 0);
```

### 3、向leader同步数据

一个节点成为follower之后，为了保证集群之间的数据一致，所以在对外服务之前要先向leader同步数据，同步的方式是根据当前follower的zxid和leader的zxid相比较而来。具体如下：

- 1、leader 首先会获取自身的minCommittedLog和 maxCommittedLog
- 2、如果 follower#lastLoggedZxid == leader#maxCommittedLog ，则发送Leader.DIFF，表示此follower和leader之间数据相同，不需要同步
- 3、如果 follower#lastLoggedZxid > leader#maxCommittedLog，则发送Leader.TRUNC和maxCommittedLog，让follower将自身数据回滚到maxCommittedLog
- 4、如果 follower#lastLoggedZxid >= leader#minCommittedLog && follower#lastLoggedZxid <= leader#maxCommittedLog，follower增量同步leader的数据
- 5、如果 follower#lastLoggedZxid < leader#minCommittedLog，优先会从txnLog同步数据，如果txnLog为空才会同步leader的snapshot

上面几个点只是大概的说明了follower 从leader同步数据的方式，这里为了简化忽略了某些条件，具体的方式可以参考LearnerHandler#syncFollower。下面我们继续看这个同步数据方法。

#### 3.1、同步snapshot

在snapshot模式下，follower和leader之间数据交互方式为：
- 1、leader 发送一个Leader.SNAP给follower
- 2、follower 收到Leader.SNAP数据包后，首先会将自己的数据清空，然后将leader发送的snapshot数据还原出新的database
- 3、设置lastProcessedZxid

核心代码如下：

```
else if (qp.getType() == Leader.SNAP) {
    // 首先会清空当前节点的数据库，然后用leader的数据初始化新的dataTree
    zk.getZKDatabase().deserializeSnapshot(leaderIs);
    
    // 设置最终处理的 zxid 为Leader的zxid
    zk.getZKDatabase().setlastProcessedZxid(qp.getZxid());
}
```

#### 3.2、follower回滚

在Leader.TRUNC模式下leader和follower的数据交互如下：
- 1、leader 发送Leader.TRUNC数据包给follower，同时也发送了自己的maxCommittedLog
- 2、follower 收到消息后回滚到leader的maxCommittedLog节点
- 3、设置lastProcessedZxid

核心代码如下：

```
else if (qp.getType() == Leader.TRUNC) {
    // 调用ZKDatabase
    boolean truncated=zk.getZKDatabase().truncateLog(qp.getZxid());
    if (!truncated) {
        // not able to truncate the log
        LOG.error("Not able to truncate the log "
                + Long.toHexString(qp.getZxid()));
        System.exit(13);
    }
    zk.getZKDatabase().setlastProcessedZxid(qp.getZxid());
}
```

#### 3.3、增量同步

在Leader.DIFF场景下，会follower会同步它和leader之间的差异数据(即follower#lastLoggedZxid >= leader#minCommittedLog && follower#lastLoggedZxid <= leader#maxCommittedLog)，它们之间的数据交互方式为：

- 1、leader发送Leader.DIFF数据包给follower
- 2、接着leader会发送follower#lastLoggedZxid到leader#maxCommittedLog之间的propose.packet,每个propose.packet之后都会紧接着发送一个Leader.COMMIT和packetZxid(packte对应的zxid)
- 3、follower首先会收到Leader.DIFF类型的数据包，它先将snapshotNeeded(是否snapshot)设为false
- 4、接着就是处理每个Leader.PROPOSAL和Leader.COMMIT包
- 5、leader发送完数据之后接着会发送Leader.NEWLEADER，然后它会阻塞等待大多数follower回传的Leader.ACK数据包
- 6、follower处理到Leader.NEWLEADER这个数据包时，说明它已经将leader的差异数据处理完成了，然后它会给leader回发一个Leader.ACK消息
- 7、leader在收到大多数follower回传的的Leader.ACK消息之后，接着会发送Leader.UPTODATE给follower
- 8、follower收到Leader.UPTODATE消息后设置ServerCnxnFactory的LearnerZooKeeperServer，然后跳出同步数据的循环

下面我们大概看下leader发送数据和follower同步数据的过程。

1、leader发送增量数据

下面的代码为Leader发送增量数据给follower的过程，其中去掉了日志代码
```
// itr = db.getCommittedLog().iterator(); leader已提交日志的迭代器
// peerLastZxid = follower.lastLoggedZxid  follower 最后的提交记录
// maxZxid = null
// lastCommittedZxid = leader#maxCommittedLog leader 最大提交记录的zxid

protected long queueCommittedProposals(Iterator<Proposal> itr,
           long peerLastZxid, Long maxZxid, Long lastCommittedZxid) {
    
    // 是否新的选举周期，集群在初次启动时该值会为true(epoch、counter 都为0)        
    boolean isPeerNewEpochZxid = (peerLastZxid & 0xffffffffL) == 0;
    long queuedZxid = peerLastZxid;

    // 前一个 Proposal 的zxid
    long prevProposalZxid = -1;
    while (itr.hasNext()) {
        Proposal propose = itr.next();

        long packetZxid = propose.packet.getZxid();
        // abort if we hit the limit
        // 如果达到maxid 则终止
        if ((maxZxid != null) && (packetZxid > maxZxid)) {
            break;
        }

        // 提过follower已提交的 Proposal
        if (packetZxid < peerLastZxid) {
            prevProposalZxid = packetZxid;
            continue;
        }

        // 如果发送第一个包的时候，支出当前操作是要什么类型（这里为DIFF 或 TRUNC）
        // 这个值初始为ture，在packetZxid >= peerLastZxid是第一次会进来
        if (needOpPacket) {

            // 如果follower 的 zxid 在 leader 的history中，则发送diff 包，
            if (packetZxid == peerLastZxid) {
                queueOpPacket(Leader.DIFF, lastCommittedZxid);
                needOpPacket = false;
                continue;
            }
            
            if (isPeerNewEpochZxid) {
               queueOpPacket(Leader.DIFF, lastCommittedZxid);
               needOpPacket = false;
            } else if (packetZxid > peerLastZxid  ) {
                
                
                // follower 的选举周期和leader的选举周期不相等，说明当中这时leader已经发生了变化，这种场景下会同步snapshot
                if (ZxidUtils.getEpochFromZxid(packetZxid) !=
                        ZxidUtils.getEpochFromZxid(peerLastZxid)) {
                    return queuedZxid;
                }
                
                // 如果进入到这个条件，follower#lastLoggedZxid >= leader#minCommittedLog && follower#lastLoggedZxid <= leader#maxCommittedLog,
                // 但是 follower#lastLoggedZxid 不在leader的事务 history中，这时需要发送一个Leader.TRUNC 包给follower，让follower回滚到prevProposalZxid
                // 然后在通过diff 的方式同步数据
                queueOpPacket(Leader.TRUNC, prevProposalZxid);
                needOpPacket = false;
            }
        }

        if (packetZxid <= queuedZxid) {
            // We can get here, if we don't have op packet to queue
            // or there is a duplicate txn in a given iterator
            continue;
        }

        // 发送数据包
        queuePacket(propose.packet);
        // 发送commit 数据包，提交packetZxid 的数据
        queueOpPacket(Leader.COMMIT, packetZxid);
        queuedZxid = packetZxid;

    }

    if (needOpPacket && isPeerNewEpochZxid) {
        // 初次发送空的diff包
        queueOpPacket(Leader.DIFF, lastCommittedZxid);
        needOpPacket = false;
    }
    
    return queuedZxid;
}  
```

2、follower同步同步数据的代码如下：

```
outerLoop:
while (self.isRunning()) {
    readPacket(qp);
    switch(qp.getType()) {
    case Leader.PROPOSAL:
        PacketInFlight pif = new PacketInFlight();
        pif.hdr = new TxnHeader();
        pif.rec = SerializeUtils.deserializeTxn(qp.getData(), pif.hdr);
        lastQueued = pif.hdr.getZxid();
        
        if (pif.hdr.getType() == OpCode.reconfig){                
            SetDataTxn setDataTxn = (SetDataTxn) pif.rec;       
           QuorumVerifier qv = self.configFromString(new String(setDataTxn.getData()));
           self.setLastSeenQuorumVerifier(qv, true);                               
        }
        
        // 将leader发送的proposal数据放到未提交列表中
        packetsNotCommitted.add(pif);
        break;
    case Leader.COMMIT:
    case Leader.COMMITANDACTIVATE:
        // 提交信息，从leader的发送代码可知，一个Leader.PROPOSAL 数据包之后会紧跟一个Leader.COMMIT，
        // 所以在收到commit的时候会按照顺序处理packetsNotCommitted队列中的数据
        pif = packetsNotCommitted.peekFirst();
        if (pif.hdr.getZxid() == qp.getZxid() && qp.getType() == Leader.COMMITANDACTIVATE) {
            QuorumVerifier qv = self.configFromString(new String(((SetDataTxn) pif.rec).getData()));
            boolean majorChange = self.processReconfig(qv, ByteBuffer.wrap(qp.getData()).getLong(),
                    qp.getZxid(), true);
            if (majorChange) {
                throw new Exception("changes proposed in reconfig");
            }
        }
        
        // 因为这里是diff的方式同步，所以需要写事务日志的，所以执行else分支，将要提交的zxid放到packetsCommitted队列
        if (!writeToTxnLog) {
            if (pif.hdr.getZxid() != qp.getZxid()) {
                LOG.warn("Committing " + qp.getZxid() + ", but next proposal is " + pif.hdr.getZxid());
            } else {
                zk.processTxn(pif.hdr, pif.rec);
                packetsNotCommitted.remove();
            }
        } else {
            packetsCommitted.add(qp.getZxid());
        }
        break;
    case Leader.INFORM:
    case Leader.INFORMANDACTIVATE:
        PacketInFlight packet = new PacketInFlight();
        packet.hdr = new TxnHeader();

        if (qp.getType() == Leader.INFORMANDACTIVATE) {
            ByteBuffer buffer = ByteBuffer.wrap(qp.getData());
            long suggestedLeaderId = buffer.getLong();
            byte[] remainingdata = new byte[buffer.remaining()];
            buffer.get(remainingdata);
            packet.rec = SerializeUtils.deserializeTxn(remainingdata, packet.hdr);
            QuorumVerifier qv = self.configFromString(new String(((SetDataTxn)packet.rec).getData()));
            boolean majorChange =
                    self.processReconfig(qv, suggestedLeaderId, qp.getZxid(), true);
            if (majorChange) {
                throw new Exception("changes proposed in reconfig");
            }
        } else {
            packet.rec = SerializeUtils.deserializeTxn(qp.getData(), packet.hdr);
            // Log warning message if txn comes out-of-order
            if (packet.hdr.getZxid() != lastQueued + 1) {
                LOG.warn("Got zxid 0x"
                        + Long.toHexString(packet.hdr.getZxid())
                        + " expected 0x"
                        + Long.toHexString(lastQueued + 1));
            }
            lastQueued = packet.hdr.getZxid();
        }
        if (!writeToTxnLog) {
            // Apply to db directly if we haven't taken the snapshot
            zk.processTxn(packet.hdr, packet.rec);
        } else {
            packetsNotCommitted.add(packet);
            packetsCommitted.add(qp.getZxid());
        }

        break;                
    case Leader.UPTODATE:
        // leader 收到ack消息后，回复UPTODATE消息给follower
        LOG.info("Learner received UPTODATE message");                                      
        if (newLeaderQV!=null) {
           boolean majorChange =
               self.processReconfig(newLeaderQV, null, null, true);
           if (majorChange) {
               throw new Exception("changes proposed in reconfig");
           }
        }
        
        // 判断当前的协议版本，在Zab V1.0 (ZK 3.4+)版本中，follower收到 NEWLEADER 消息后会做一次 snapshot，但是在V1.0以前，在收到UPDATE消息后也会做snapshot。
        // 但是在 V1.0 中，在 收到 NEWLEADER消息后还会再次收到 UPDATE的消息，这个标志位就是确保在V1.0版本不会做两次snapshot。
        if (isPreZAB1_0) {
            zk.takeSnapshot();
            self.setCurrentEpoch(newEpoch);
        }
        self.setZooKeeperServer(zk);
        self.adminServer.setZooKeeperServer(zk);
        // 跳出当前的数据同步
        break outerLoop;
    case Leader.NEWLEADER: // leader在发送完数据后会发送一个Leader.NEWLEADER，代表当前leader的数据已经发送完成
       if (qp.getData()!=null && qp.getData().length > 1) {
           try {                       
               QuorumVerifier qv = self.configFromString(new String(qp.getData()));
               self.setLastSeenQuorumVerifier(qv, true);
               newLeaderQV = qv;
           } catch (Exception e) {
               e.printStackTrace();
           }
       }
       
       // 是否要做一次 snapshot，只有在diff的情况下为false
       if (snapshotNeeded) {
           zk.takeSnapshot();
       }
       
        self.setCurrentEpoch(newEpoch);
        writeToTxnLog = true; // 在
        isPreZAB1_0 = false;
        
        // 收到Leader.NEWLEADER后，回复ACK消息给leader
        writePacket(new QuorumPacket(Leader.ACK, newLeaderZxid, null, null), true);
        break;
    }
}

```

以上就是如果当前节点被确定为follower后，它同leader之间的数据同步过程，在数据同步完成之前follower本身是不能对外服务的。
在数据同步完成之后，启动LearnerZookeeperServer，设置请求处理器，然后开始对外服务。
