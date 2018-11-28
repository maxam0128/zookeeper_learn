# QuorumPeer

参考：
1、https://blog.reactor.top/2018/04/09/zookeeper%E6%BA%90%E7%A0%81-ZAB%E5%8D%8F%E8%AE%AE%E4%B9%8B%E9%9B%86%E7%BE%A4%E5%90%8C%E6%AD%A5_3/
2、http://iwinit.iteye.com/blog/1775439

这个类主要管理提议者协议的，它在服务器内部有三种状态：

> Leader election: 选举阶段(初始化的时候提议自己为Leader)
>
> Follower: 从Leader 哪里同步复制每一个事务
>
> Leader: 处理请求并且把他们转发给每个Follower，大多数的Follower在接受请求时必须先记录日志

下面分别解析当前服务器处于上述几个状态时的功能特点。

## Leader election

### 只读模式
 
#### 配置 

只读模式配置: readonlymode.enabled

```

// 创建一个只读的服务器，但是当前并不立刻启动它，后续再详细看这个只读服务的具体实现
final ReadOnlyZooKeeperServer roZk =
    new ReadOnlyZooKeeperServer(logFactory, this, this.zkDb);

// 延时启动只读服务的包装线程
Thread roZkMgr = new Thread() {
    public void run() {
        try {
            // lower-bound grace period to 2 secs
            // 等待一定周期后，启动只读服务
            sleep(Math.max(2000, tickTime));
            if (ServerState.LOOKING.equals(getPeerState())) {
                roZk.startup();
            }
        } catch (InterruptedException e) {
            LOG.info("Interrupted while attempting to start ReadOnlyZooKeeperServer, not started");
        } catch (Exception e) {
            LOG.error("FAILED to start ReadOnlyZooKeeperServer", e);
        }
    }
};
try {
    roZkMgr.start();
    
    reconfigFlagClear();
    if (shuttingDownLE) {
        shuttingDownLE = false;
        // 启动选举，这里还没有开始真正的选举，只是初始化了当前的服务的投票信息和选举算法
        startLeaderElection();
    }
    
    // 通过 lookForLeader 开始选举，并将选举结果告诉当前节点
    setCurrentVote(makeLEStrategy().lookForLeader());
} catch (Exception e) {
    LOG.warn("Unexpected exception", e);
    setPeerState(ServerState.LOOKING);
} finally {
    // If the thread is in the the grace period, interrupt
    // to come out of waiting.
    roZkMgr.interrupt();
    roZk.shutdown();
}
```

### 非只读

如果没有配置 readonlymode.enabled 这个参数，或者配置为false，那么会直接进行选举工作，这部分代码比较简单，如下所示：

```text

// 代码解析如上所述
try {
   reconfigFlagClear();
    if (shuttingDownLE) {
       shuttingDownLE = false;
       startLeaderElection();
       }
    setCurrentVote(makeLEStrategy().lookForLeader());
} catch (Exception e) {
    LOG.warn("Unexpected exception", e);
    setPeerState(ServerState.LOOKING);
}  
```

在选举过程中，会根据选举结果更新当前节点的状态，具体参考选举过程中的代码分析。

## Follower

ZK服务节点在启动时初始状态都为ServerState.LOOKING，那么在选举完成后，如果当前的节点状态变为ServerState.FOLLOWING，那么会接下来当前节点扮演的角色会执行什么操作呢，下面我们详细分析。

```text

try {
    LOG.info("FOLLOWING");
    
    // 将当前节点设置为Follower   
    setFollower(makeFollower(logFactory));
    
    // follow leader
    follower.followLeader();
} catch (Exception e) {

   // 如果 follower 和 leader 通信断开，则会抛出IOException
   LOG.warn("Unexpected exception",e);
} finally {
   follower.shutdown();
   setFollower(null);
   
   // 如果发生异常，则重新进入选举阶段
   updateServerState();
}

```

### Learner 


下面我们主要看下 Follower#followLeader 方法实现：

```

void followLeader() throws InterruptedException {
    self.end_fle = Time.currentElapsedTime();
    long electionTimeTaken = self.end_fle - self.start_fle;
    self.setElectionTimeTaken(electionTimeTaken);
    LOG.info("FOLLOWING - LEADER ELECTION TOOK - {} {}", electionTimeTaken,
            QuorumPeer.FLE_TIME_UNIT);
    self.start_fle = 0;
    self.end_fle = 0;
    
    // 注册 FollowerBean
    fzk.registerJMX(new FollowerBean(this, zk), self.jmxLocalPeerBean);
    try {
        
        // 查找 Leader，这是 Follower父类 Learner#findLeader,
        // 实现比较简单，根据当前的 leader id 从服务节点视图中找出Leader节点的信息
        QuorumServer leaderServer = findLeader();            
        try {
        
            // 尝试连接到Leader 节点，在 initLimitTime 时间内会重试5次
            // initLimitTime = self.tickTime * self.initLimit
            connectToLeader(leaderServer.addr, leaderServer.hostname);
            
            // 连接成功，执行握手协议，和Leader建立 Follower / Observer 连接，并同步Leader 最新的 Zxid
            // 1、首先Follower 会将自身的zxid等信息发送给Leader
            // 2、Leader 收到信息后也会将自己的Zxid,type等信息回传给Follower
            // 3、Follower 收到信息后，解析出Leader 的Zxid并和自身比较
            // 4、如果 Leader.newEpoch > self.getAcceptedEpoch(),则更新自身的epoch
            // 5、如果是Follower 是启动后初次发消息给Leader，则还需要发送ack 消息给Leader
            long newEpochZxid = registerWithLeader(Leader.FOLLOWERINFO);
            if (self.isReconfigStateChange())
               throw new Exception("learned about role change");
            //check to see if the leader zxid is lower than ours
            //this should never happen but is just a safety check
            long newEpoch = ZxidUtils.getEpochFromZxid(newEpochZxid);
            if (newEpoch < self.getAcceptedEpoch()) {
                LOG.error("Proposed leader epoch " + ZxidUtils.zxidToString(newEpochZxid)
                        + " is less than our accepted epoch " + ZxidUtils.zxidToString(self.getAcceptedEpoch()));
                throw new IOException("Error: Epoch of leader is lower");
            }
            
            // 根据 newZxid 从Leader同步数据
            syncWithLeader(newEpochZxid);
            
            // 数据同步完成后，然后处理 leader 的请求                
            QuorumPacket qp = new QuorumPacket();
            while (this.isRunning()) {
                
                readPacket(qp);
                processPacket(qp);
            }
        } catch (Exception e) {
            LOG.warn("Exception when following the leader", e);
            try {
                sock.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }

            // clear pending revalidations
            pendingRevalidations.clear();
        }
    } finally {
        zk.unregisterJMX((Learner)this);
    }
}
```

Learner#syncWithLeader 

```

protected void syncWithLeader(long newLeaderZxid) throws Exception{
    
    // 构造ack 消息包
    QuorumPacket ack = new QuorumPacket(Leader.ACK, 0, null, null);
    
    
    QuorumPacket qp = new QuorumPacket();
    long newEpoch = ZxidUtils.getEpochFromZxid(newLeaderZxid);
    
    QuorumVerifier newLeaderQV = null;
    
    // In the DIFF case we don't need to do a snapshot because the transactions will sync on top of any existing snapshot
    // For SNAP and TRUNC the snapshot is needed to save that history
    
    // 从上面注释可知，在DIFF的情形下是不需要做snapshot的，因为事务的同步都是在已存在的snapshot的顶端开始的
    // 在 SNAP 和 TRUNC 情形下下才需要保存snapshot
    boolean snapshotNeeded = true;
    
    // 读取数据
    readPacket(qp);
    LinkedList<Long> packetsCommitted = new LinkedList<Long>();
    LinkedList<PacketInFlight> packetsNotCommitted = new LinkedList<PacketInFlight>();
    synchronized (zk) {
        if (qp.getType() == Leader.DIFF) {
            LOG.info("Getting a diff from the leader 0x{}", Long.toHexString(qp.getZxid()));
            snapshotNeeded = false;
        }
        else if (qp.getType() == Leader.SNAP) {
            LOG.info("Getting a snapshot from leader 0x" + Long.toHexString(qp.getZxid()));
            // The leader is going to dump the database
            // db is clear as part of deserializeSnapshot()
            // =======================================
            // 1、first clear zk db
            // 2、second use leader input deserialize zk db 
            // 首先会清空 zk db，其次将leader的数据恢复到当前的 db
            zk.getZKDatabase().deserializeSnapshot(leaderIs);
            
            // ZOOKEEPER-2819: overwrite config node content extracted
            // from leader snapshot with local config, to avoid potential
            // inconsistency of config node content during rolling restart.
            
            if (!QuorumPeerConfig.isReconfigEnabled()) {
                
                // 在恢复 zk db 后，
                LOG.debug("Reset config node content from local config after deserialization of snapshot.");
                zk.getZKDatabase().initConfigInZKDatabase(self.getQuorumVerifier());
            }
            String signature = leaderIs.readString("signature");
            if (!signature.equals("BenWasHere")) {
                LOG.error("Missing signature. Got " + signature);
                throw new IOException("Missing signature");                   
            }
            
            // 设置最新的zxid
            zk.getZKDatabase().setlastProcessedZxid(qp.getZxid());
        } else if (qp.getType() == Leader.TRUNC) {
        
            //we need to truncate the log to the lastzxid of the leader
            // 从leader的 zxid 处截断日志
            LOG.warn("Truncating log to get in sync with the leader 0x"
                    + Long.toHexString(qp.getZxid()));
            boolean truncated=zk.getZKDatabase().truncateLog(qp.getZxid());
            if (!truncated) {
                // not able to truncate the log
                LOG.error("Not able to truncate the log "
                        + Long.toHexString(qp.getZxid()));
                System.exit(13);
            }
            
            // 将从leader 同步的zxid设置为当前
            zk.getZKDatabase().setlastProcessedZxid(qp.getZxid());

        }
        else {
            LOG.error("Got unexpected packet from leader: {}, exiting ... ",
                      LearnerHandler.packetToString(qp));
            System.exit(13);

        }
        
        zk.getZKDatabase().initConfigInZKDatabase(self.getQuorumVerifier());
        
        // session过期管理类
        zk.createSessionTracker();            
        
        long lastQueued = 0;

        // in Zab V1.0 (ZK 3.4+) we might take a snapshot when we get the NEWLEADER message, but in pre V1.0
        // we take the snapshot on the UPDATE message, since Zab V1.0 also gets the UPDATE (after the NEWLEADER)
        // we need to make sure that we don't take the snapshot twice.
        
        // 在 Zab V1.0(ZK 3.4+)中，当收到 NEWLEADER 的信息后，会记录一次snapshot，但是在V1.0以前，会将更新信息做snapshot。
        // 在Zab V1.0 中，在NEWLEADER之后，也会获取更新信息，所以这里确保不会做两次snapshot
        boolean isPreZAB1_0 = true;
        
        //If we are not going to take the snapshot be sure the transactions are not applied in memory
        // but written out to the transaction log
        
        // 事务日志和快照二选一
        boolean writeToTxnLog = !snapshotNeeded;
        // we are now going to start getting transactions to apply followed by an UPTODATE
        // 开始获取事务日志并且应用更新
        outerLoop:
        
        // 处理收到的消息
        while (self.isRunning()) {
            readPacket(qp);
            switch(qp.getType()) {
            
            // 处理提议 PROPOSAL 消息
            case Leader.PROPOSAL:
                PacketInFlight pif = new PacketInFlight();
                pif.hdr = new TxnHeader();
                pif.rec = SerializeUtils.deserializeTxn(qp.getData(), pif.hdr);
                if (pif.hdr.getZxid() != lastQueued + 1) {
                LOG.warn("Got zxid 0x"
                        + Long.toHexString(pif.hdr.getZxid())
                        + " expected 0x"
                        + Long.toHexString(lastQueued + 1));
                }
                lastQueued = pif.hdr.getZxid();
                
                if (pif.hdr.getType() == OpCode.reconfig){                
                    SetDataTxn setDataTxn = (SetDataTxn) pif.rec;      
                   // 重配置 
                   QuorumVerifier qv = self.configFromString(new String(setDataTxn.getData()));
                   self.setLastSeenQuorumVerifier(qv, true);                               
                }
                
                // 将所有收到的 PROPOSAL 消息先加入到 packetsNotCommitted 列表
                packetsNotCommitted.add(pif);
                break;
            case Leader.COMMIT:
            case Leader.COMMITANDACTIVATE:
            
                // 按顺序提交所有的 PROPOSAL，先收到的 PROPOSAL 先提交 
                pif = packetsNotCommitted.peekFirst();
                
                // 
                if (pif.hdr.getZxid() == qp.getZxid() && qp.getType() == Leader.COMMITANDACTIVATE) {
                    QuorumVerifier qv = self.configFromString(new String(((SetDataTxn) pif.rec).getData()));
                    
                    // 判断是否大多数节点已经处理过
                    boolean majorChange = self.processReconfig(qv, ByteBuffer.wrap(qp.getData()).getLong(),
                            qp.getZxid(), true);
                    if (majorChange) {
                        throw new Exception("changes proposed in reconfig");
                    }
                }
                
                if (!writeToTxnLog) {
                
                    // 当前不事务日志，数据提交后会写snapshot
                    if (pif.hdr.getZxid() != qp.getZxid()) {
                        LOG.warn("Committing " + qp.getZxid() + ", but next proposal is " + pif.hdr.getZxid());
                    } else {
                        
                        // 处理事务消息
                        zk.processTxn(pif.hdr, pif.rec);
                        packetsNotCommitted.remove();
                    }
                } else {
                    
                    // 对于已经提交的消息写事务日志，按照提交顺序写
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
                LOG.info("Learner received UPTODATE message");                                      
                if (newLeaderQV!=null) {
                   boolean majorChange =
                       self.processReconfig(newLeaderQV, null, null, true);
                   if (majorChange) {
                       throw new Exception("changes proposed in reconfig");
                   }
                }
                if (isPreZAB1_0) {
                    zk.takeSnapshot();
                    self.setCurrentEpoch(newEpoch);
                }
                self.setZooKeeperServer(zk);
                self.adminServer.setZooKeeperServer(zk);
                break outerLoop;
            case Leader.NEWLEADER: // Getting NEWLEADER here instead of in discovery 
                // means this is Zab 1.0
               LOG.info("Learner received NEWLEADER message");
               if (qp.getData()!=null && qp.getData().length > 1) {
                   try {                       
                       QuorumVerifier qv = self.configFromString(new String(qp.getData()));
                       self.setLastSeenQuorumVerifier(qv, true);
                       newLeaderQV = qv;
                   } catch (Exception e) {
                       e.printStackTrace();
                   }
               }

               if (snapshotNeeded) {
                   zk.takeSnapshot();
               }
               
                self.setCurrentEpoch(newEpoch);
                writeToTxnLog = true; //Anything after this needs to go to the transaction log, not applied directly in memory
                isPreZAB1_0 = false;
                writePacket(new QuorumPacket(Leader.ACK, newLeaderZxid, null, null), true);
                break;
            }
        }
    }
    
    // todo : 上面的暂时先不看了，太复杂了 - -||
    ack.setZxid(ZxidUtils.makeZxid(newEpoch, 0));
    writePacket(ack, true);
    sock.setSoTimeout(self.tickTime * self.syncLimit);
    
    // 如果从leader 哪里同步数据完毕，则启动Follower 服务，
    // 1、创建sessionTracker(if sessionTracker == null)，并启动sessionTracker，主要作用启动一个后台线程管理sessionExpiryQueue，来实现过期session的清楚
    // 2、设置请求处理器，这里是责任链的设计模式，所以Follower 处理请求的链路为：
    // FollowerRequestProcessor -> CommitProcessor -> FinalRequestProcessor(事务信息最后通过FinalRequestProcessor -> ZKDatabase#addCommittedProposal)。最后是设置同步请求
    // 3、最后是注册JMX、将当前节点的状态设置为 RUNNING
    zk.startup();
    /*
     * Update the election vote here to ensure that all members of the
     * ensemble report the same vote to new servers that start up and
     * send leader election notifications to the ensemble.
     * 
     * @see https://issues.apache.org/jira/browse/ZOOKEEPER-1732
     */
    
    // 更新这一轮的选举信息
    self.updateElectionVote(newEpoch);

    // We need to log the stuff that came in between the snapshot and the uptodate
    
    // 下面两个分别对 follower 和observer 的处理
    if (zk instanceof FollowerZooKeeperServer) {
        FollowerZooKeeperServer fzk = (FollowerZooKeeperServer)zk;
        
        // 记录同步请求数据
        for(PacketInFlight p: packetsNotCommitted) {
            fzk.logRequest(p.hdr, p.rec);
        }
        
        // 提交已经在leader端提交的zxid
        for(Long zxid: packetsCommitted) {
            fzk.commit(zxid);
        }
    } else if (zk instanceof ObserverZooKeeperServer) {
        // Similar to follower, we need to log requests between the snapshot
        // and UPTODATE
        ObserverZooKeeperServer ozk = (ObserverZooKeeperServer) zk;
        for (PacketInFlight p : packetsNotCommitted) {
            Long zxid = packetsCommitted.peekFirst();
            if (p.hdr.getZxid() != zxid) {
                // log warning message if there is no matching commit
                // old leader send outstanding proposal to observer
                LOG.warn("Committing " + Long.toHexString(zxid)
                        + ", but next proposal is "
                        + Long.toHexString(p.hdr.getZxid()));
                continue;
            }
            packetsCommitted.remove();
            Request request = new Request(null, p.hdr.getClientId(),
                    p.hdr.getCxid(), p.hdr.getType(), null, null);
            request.setTxn(p.rec);
            request.setHdr(p.hdr);
            ozk.commitRequest(request);
        }
    } else {
        // New server type need to handle in-flight packets
        throw new UnsupportedOperationException("Unknown server type");
    }
}
```

> Leader.DIFF Follower只从Leader同步增量数据
>
>Leader.TRUNC Follower 的zxid比Leader大，这时候Follower 回滚Leader zxid 后面的数据
>
>Leader.SNAP Follower 从Leader download snapshot 数据
>

follower 启动后，处理来自Leader的信息包。

Follower#processPacket
```text

protected void processPacket(QuorumPacket qp) throws Exception{
    switch (qp.getType()) {
    case Leader.PING:            
        ping(qp);            
        break;
    case Leader.PROPOSAL:           
        TxnHeader hdr = new TxnHeader();
        Record txn = SerializeUtils.deserializeTxn(qp.getData(), hdr);
        if (hdr.getZxid() != lastQueued + 1) {
            
            // 如果进入到这里，说明当前follower的事务和leader的事务出现了断层的现象，比如 follower 目前zxid = 5，leader 的 zxid = 7，说明follower 由于各种原因中间和leader断了
            // 可能是网络抖动，连接断开，或者网络堵塞导致leader发过来的最后一个事务先到了
            LOG.warn("Got zxid 0x"
                    + Long.toHexString(hdr.getZxid())
                    + " expected 0x"
                    + Long.toHexString(lastQueued + 1));
        }
        
        // 更新当前follower的zxid
        lastQueued = hdr.getZxid();
        
        if (hdr.getType() == OpCode.reconfig){
           
           // 更新配置信息
           SetDataTxn setDataTxn = (SetDataTxn) txn;       
           QuorumVerifier qv = self.configFromString(new String(setDataTxn.getData()));
           self.setLastSeenQuorumVerifier(qv, true);                               
        }
        
        // 对于 PROPOSAL 请求，是先写日志
        fzk.logRequest(hdr, txn);
        break;
    case Leader.COMMIT:
    
        // 提交 zxid 对应的 PROPOSAL
        fzk.commit(qp.getZxid());
        break;
        
    case Leader.COMMITANDACTIVATE:
       // get the new configuration from the request
       // 从 leader 收到更新配置的请求，更新当前follower的配置信息
       Request request = fzk.pendingTxns.element();
       SetDataTxn setDataTxn = (SetDataTxn) request.getTxn();                                                                                                      
       QuorumVerifier qv = self.configFromString(new String(setDataTxn.getData()));                                

       // get new designated leader from (current) leader's message
       ByteBuffer buffer = ByteBuffer.wrap(qp.getData());    
       long suggestedLeaderId = buffer.getLong();
        boolean majorChange = 
               self.processReconfig(qv, suggestedLeaderId, qp.getZxid(), true);
       // commit (writes the new config to ZK tree (/zookeeper/config)                     
       fzk.commit(qp.getZxid());
        if (majorChange) {
           throw new Exception("changes proposed in reconfig");
       }
       break;
    case Leader.UPTODATE:
        LOG.error("Received an UPTODATE message after Follower started");
        break;
    case Leader.REVALIDATE:
        revalidate(qp);
        break;
    case Leader.SYNC:
        fzk.sync();
        break;
    default:
        LOG.warn("Unknown packet type: {}", LearnerHandler.packetToString(qp));
        break;
    }
}
```


## Leader 

如果当前节点成为了Leader节点，接下来我们看下Leader执行的主要功能：

```text

LOG.info("LEADING");
try {
    
    // 创建leader 
    setLeader(makeLeader(logFactory));
    
    //
    leader.lead();
    setLeader(null);
} catch (Exception e) {

    LOG.warn("Unexpected exception",e);
} finally {

    // 如果leader 出现异常，则强制关闭leader
    if (leader != null) {
        leader.shutdown("Forcing shutdown");
        setLeader(null);
    }
    updateServerState();
}
```

### Leader#lead 

```text

// 1、
// 2、
// 启动 LeaderZooKeeperServer， startZkServer,
// 启动管理服务

void lead() throws IOException, InterruptedException {
    self.end_fle = Time.currentElapsedTime();
    long electionTimeTaken = self.end_fle - self.start_fle;
    self.setElectionTimeTaken(electionTimeTaken);
    LOG.info("LEADING - LEADER ELECTION TOOK - {} {}", electionTimeTaken,
            QuorumPeer.FLE_TIME_UNIT);
    self.start_fle = 0;
    self.end_fle = 0;

    zk.registerJMX(new LeaderBean(this, zk), self.jmxLocalPeerBean);

    try {
        self.tick.set(0);
        
        // 1、理论上不会再次加载数据(在选举前已经加载过数据)，通过QuorumPeer#getLastLoggedZxid 来获取zxid作为初次的投票信息
        // 2、删除过期的session
        // 3、将当前的数据保存一次snapshot
        zk.loadData();

        leaderStateSummary = new StateSummary(self.getCurrentEpoch(), zk.getLastProcessedZxid());

        // 开启新的线程等待 follower 的连接请求
        // 这里详情参考LearHandler 
        cnxAcceptor = new LearnerCnxAcceptor();
        cnxAcceptor.start();

        long epoch = getEpochToPropose(self.getId(), self.getAcceptedEpoch());

        zk.setZxid(ZxidUtils.makeZxid(epoch, 0));

        synchronized(this){
            lastProposed = zk.getZxid();
        }

        newLeaderProposal.packet = new QuorumPacket(NEWLEADER, zk.getZxid(),
               null, null);


        if ((newLeaderProposal.packet.getZxid() & 0xffffffffL) != 0) {
            LOG.info("NEWLEADER proposal has Zxid of "
                    + Long.toHexString(newLeaderProposal.packet.getZxid()));
        }
    
        // 最后的提案配置
        QuorumVerifier lastSeenQV = self.getLastSeenQuorumVerifier();
        
        // 最后的提交配置
        QuorumVerifier curQV = self.getQuorumVerifier();
        if (curQV.getVersion() == 0 && curQV.getVersion() == lastSeenQV.getVersion()) {
            // This was added in ZOOKEEPER-1783. The initial config has version 0 (not explicitly
            // specified by the user; the lack of version in a config file is interpreted as version=0). 
            // As soon as a config is established we would like to increase its version so that it
            // takes presedence over other initial configs that were not established (such as a config
            // of a server trying to join the ensemble, which may be a partial view of the system, not the full config). 
            // We chose to set the new version to the one of the NEWLEADER message. However, before we can do that
            // there must be agreement on the new version, so we can only change the version when sending/receiving UPTODATE,
            // not when sending/receiving NEWLEADER. In other words, we can't change curQV here since its the committed quorum verifier, 
            // and there's still no agreement on the new version that we'd like to use. Instead, we use 
            // lastSeenQuorumVerifier which is being sent with NEWLEADER message
            // so its a good way to let followers know about the new version. (The original reason for sending 
            // lastSeenQuorumVerifier with NEWLEADER is so that the leader completes any potentially uncommitted reconfigs
            // that it finds before starting to propose operations. Here we're reusing the same code path for 
            // reaching consensus on the new version number.)
            
            // It is important that this is done before the leader executes waitForEpochAck,
            // so before LearnerHandlers return from their waitForEpochAck
            // hence before they construct the NEWLEADER message containing
            // the last-seen-quorumverifier of the leader, which we change below
           try {
               QuorumVerifier newQV = self.configFromString(curQV.toString());
               newQV.setVersion(zk.getZxid());
               self.setLastSeenQuorumVerifier(newQV, true);    
           } catch (Exception e) {
               throw new IOException(e);
           }
        }
        
        newLeaderProposal.addQuorumVerifier(self.getQuorumVerifier());
        if (self.getLastSeenQuorumVerifier().getVersion() > self.getQuorumVerifier().getVersion()){
           newLeaderProposal.addQuorumVerifier(self.getLastSeenQuorumVerifier());
        }
        
        // We have to get at least a majority of servers in sync with
        // us. We do this by waiting for the NEWLEADER packet to get
        // acknowledged
                   
         waitForEpochAck(self.getId(), leaderStateSummary);
         self.setCurrentEpoch(epoch);    
        
         try {
             waitForNewLeaderAck(self.getId(), zk.getZxid());
         } catch (InterruptedException e) {
             shutdown("Waiting for a quorum of followers, only synced with sids: [ "
                     + newLeaderProposal.ackSetsToString() + " ]");
             HashSet<Long> followerSet = new HashSet<Long>();

             for(LearnerHandler f : getLearners()) {
                 if (self.getQuorumVerifier().getVotingMembers().containsKey(f.getSid())){
                     followerSet.add(f.getSid());
                 }
             }    
             boolean initTicksShouldBeIncreased = true;
             for (Proposal.QuorumVerifierAcksetPair qvAckset:newLeaderProposal.qvAcksetPairs) {
                 if (!qvAckset.getQuorumVerifier().containsQuorum(followerSet)) {
                     initTicksShouldBeIncreased = false;
                     break;
                 }
             }                  
             if (initTicksShouldBeIncreased) {
                 LOG.warn("Enough followers present. "+
                         "Perhaps the initTicks need to be increased.");
             }
             return;
         }

         startZkServer();
         
        /**
         * WARNING: do not use this for anything other than QA testing
         * on a real cluster. Specifically to enable verification that quorum
         * can handle the lower 32bit roll-over issue identified in
         * ZOOKEEPER-1277. Without this option it would take a very long
         * time (on order of a month say) to see the 4 billion writes
         * necessary to cause the roll-over to occur.
         *
         * This field allows you to override the zxid of the server. Typically
         * you'll want to set it to something like 0xfffffff0 and then
         * start the quorum, run some operations and see the re-election.
         */
        String initialZxid = System.getProperty("zookeeper.testingonly.initialZxid");
        if (initialZxid != null) {
            long zxid = Long.parseLong(initialZxid);
            zk.setZxid((zk.getZxid() & 0xffffffff00000000L) | zxid);
        }

        if (!System.getProperty("zookeeper.leaderServes", "yes").equals("no")) {
            self.setZooKeeperServer(zk);
        }

        self.adminServer.setZooKeeperServer(zk);

        // Everything is a go, simply start counting the ticks
        // WARNING: I couldn't find any wait statement on a synchronized
        // block that would be notified by this notifyAll() call, so
        // I commented it out
        //synchronized (this) {
        //    notifyAll();
        //}
        // We ping twice a tick, so we only update the tick every other
        // iteration
        boolean tickSkip = true;
        // If not null then shutdown this leader
        String shutdownMessage = null;

        while (true) {
            synchronized (this) {
                long start = Time.currentElapsedTime();
                long cur = start;
                long end = start + self.tickTime / 2;
                while (cur < end) {
                    wait(end - cur);
                    cur = Time.currentElapsedTime();
                }

                if (!tickSkip) {
                    self.tick.incrementAndGet();
                }

                // We use an instance of SyncedLearnerTracker to
                // track synced learners to make sure we still have a
                // quorum of current (and potentially next pending) view.
                SyncedLearnerTracker syncedAckSet = new SyncedLearnerTracker();
                syncedAckSet.addQuorumVerifier(self.getQuorumVerifier());
                if (self.getLastSeenQuorumVerifier() != null
                        && self.getLastSeenQuorumVerifier().getVersion() > self
                                .getQuorumVerifier().getVersion()) {
                    syncedAckSet.addQuorumVerifier(self
                            .getLastSeenQuorumVerifier());
                }

                syncedAckSet.addAck(self.getId());

                for (LearnerHandler f : getLearners()) {
                    if (f.synced()) {
                        syncedAckSet.addAck(f.getSid());
                    }
                }

                // check leader running status
                if (!this.isRunning()) {
                    // set shutdown flag
                    shutdownMessage = "Unexpected internal error";
                    break;
                }

                if (!tickSkip && !syncedAckSet.hasAllQuorums()) {
                    // Lost quorum of last committed and/or last proposed
                    // config, set shutdown flag
                    shutdownMessage = "Not sufficient followers synced, only synced with sids: [ "
                            + syncedAckSet.ackSetsToString() + " ]";
                    break;
                }
                tickSkip = !tickSkip;
            }
            for (LearnerHandler f : getLearners()) {
                f.ping();
            }
        }
        if (shutdownMessage != null) {
            shutdown(shutdownMessage);
            // leader goes in looking state
        }
    } finally {
        zk.unregisterJMX(this);
    }
}
```

