# Leader 和 Follower 初始化

在选主结束后会更新当前节点的状态，随后就根据当前节点的状态将当前节点初始化对应的角色。下面我们主要看下Leader和Follower两种角色的初始化。

## Leader 初始化

当节点的状态为 LEADING 时，会将当前节点初始化为Leader节点，然后开始 lead 过程，代码如下：
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
### lead 过程：

```

self.tick.set(0);

// 1、理论上不会再次加载数据(在选举前已经加载过数据)，通过QuorumPeer#getLastLoggedZxid 来获取zxid作为初次的投票信息
// 2、删除过期的session
// 3、将当前的数据保存一次snapshot
zk.loadData();

leaderStateSummary = new StateSummary(self.getCurrentEpoch(), zk.getLastProcessedZxid());

// 开启新的线程等待 follower 的连接请求
// 这里会对每一个 Follower 的连接请求启动一个LearHandler，详情参考 LearHandler
cnxAcceptor = new LearnerCnxAcceptor();
cnxAcceptor.start();

// 这里会阻塞，直到已经有大多数的Follower(包括它自己)已经连接到Leader
long epoch = getEpochToPropose(self.getId(), self.getAcceptedEpoch());

```

启动LearnerCnxAcceptor，接受来自Follower的连接请求，并为每一个Follower 请求启动一个LearHandler。

```
public void run() {
    try {
        while (!stop) {
            try{
                
                // 等待Follower 的连接
                Socket s = ss.accept();
                // start with the initLimit, once the ack is processed
                // in LearnerHandler switch to the syncLimit
                s.setSoTimeout(self.tickTime * self.initLimit);
                s.setTcpNoDelay(nodelay);

                BufferedInputStream is = new BufferedInputStream(
                        s.getInputStream());
                
                // 为每一个Follower 启动一个LearnerHandler 线程
                LearnerHandler fh = new LearnerHandler(s, is, Leader.this);
                fh.start();
            } catch (SocketException e) {
                if (stop) {
                    LOG.info("exception while shutting down acceptor: "
                            + e);

                    // When Leader.shutdown() calls ss.close(),
                    // the call to accept throws an exception.
                    // We catch and set stop to true.
                    stop = true;
                } else {
                    throw e;
                }
            } catch (SaslException e){
                LOG.error("Exception while connecting to quorum learner", e);
            }
        }
    } catch (Exception e) {
        LOG.warn("Exception while accepting follower", e.getMessage());
        
        // 异常处理
        handleException(this.getName(), e);
    }
}

```
LearnerHandler#run

处理来自follower 的请求，一个LearnerHandler只处理一个follower的请求。

```

// 将handler 添加到leader中
leader.addLearnerHandler(this);

// 
tickOfNextAckDeadline = leader.self.tick.get()
        + leader.self.initLimit + leader.self.syncLimit;

ia = BinaryInputArchive.getArchive(bufferedInput);
bufferedOutput = new BufferedOutputStream(sock.getOutputStream());
oa = BinaryOutputArchive.getArchive(bufferedOutput);

// 读取 follower 发送过来的数据
QuorumPacket qp = new QuorumPacket();
ia.readRecord(qp, "packet");

// 可以看出，如果follower 初始发送的不是自身的信息则退出当前的处理Leader.FOLLOWERINFO
// follower 初次发送消息是 Follower#followLeader -> Learner#registerWithLeader
if(qp.getType() != Leader.FOLLOWERINFO && qp.getType() != Leader.OBSERVERINFO){
    LOG.error("First packet " + qp.toString()
            + " is not FOLLOWERINFO or OBSERVERINFO!");
    return;
}
```

读取follower中的数据，并确定新的选举轮次
```

// 读取Follower 发送的data数据，从follower 中可以看出，这个data是一个 LearnerInfo 对象
byte learnerInfoData[] = qp.getData();
if (learnerInfoData != null) {
    
    // 读取 LearnerInfo 中的数据，具体可参考 LearnerInfo
    ByteBuffer bbsid = ByteBuffer.wrap(learnerInfoData);
    if (learnerInfoData.length >= 8) {
        this.sid = bbsid.getLong();
    }
    // protocolVersion，默认为0x10000
    if (learnerInfoData.length >= 12) {
        this.version = bbsid.getInt(); // protocolVersion
    }
    
    // configVersion
    if (learnerInfoData.length >= 20) {
        long configVersion = bbsid.getLong();
        if (configVersion > leader.self.getQuorumVerifier().getVersion()) {
            throw new IOException("Follower is ahead of the leader (has a later activated configuration)");
        }
    }
} else {
    this.sid = leader.followerCounter.getAndDecrement();
}

if (leader.self.getView().containsKey(this.sid)) {
    LOG.info("Follower sid: " + this.sid + " : info : "
            + leader.self.getView().get(this.sid).toString());
} else {
    LOG.info("Follower sid: " + this.sid + " not in the current config " + Long.toHexString(leader.self.getQuorumVerifier().getVersion()));
}

if (qp.getType() == Leader.OBSERVERINFO) {
      learnerType = LearnerType.OBSERVER;
}

// 从follower的zxid 中解析出 epoch
long lastAcceptedEpoch = ZxidUtils.getEpochFromZxid(qp.getZxid());

long peerLastZxid;
StateSummary ss = null;
long zxid = qp.getZxid();

// 通过leader 同步选举的轮次，这里在leader收到大多数follower连接前，会一直阻塞，代码详情，请看下面分析
long newEpoch = leader.getEpochToPropose(this.getSid(), lastAcceptedEpoch);
```
和follower确认最新的选举轮次，并计算最新的zxid

```
// 根据新的选举轮次，构造新最新的zxid
long newLeaderZxid = ZxidUtils.makeZxid(newEpoch, 0);

// 每个follower初次发送的版本信息默认为0x10000，所以默认执行else代码段
if (this.getVersion() < 0x10000) {
    // we are going to have to extrapolate the epoch information
    long epoch = ZxidUtils.getEpochFromZxid(zxid);
    ss = new StateSummary(epoch, zxid);
    // fake the message
    leader.waitForEpochAck(this.getSid(), ss);
} else {
    byte ver[] = new byte[4];
    ByteBuffer.wrap(ver).putInt(0x10000);
    
    // 构建新的 QuorumPacket，回传给follower
    QuorumPacket newEpochPacket = new QuorumPacket(Leader.LEADERINFO, newLeaderZxid, ver, null);
    oa.writeRecord(newEpochPacket, "packet");
    bufferedOutput.flush();
    QuorumPacket ackEpochPacket = new QuorumPacket();
    ia.readRecord(ackEpochPacket, "packet");
    
    // 理论上，follower 收到leader 发送的 newEpoch 消息后，会回复一个 Leader.ACKEPOCH的消息
    if (ackEpochPacket.getType() != Leader.ACKEPOCH) {
        LOG.error(ackEpochPacket.toString()
                + " is not ACKEPOCH");
        return;
    }
    
    ByteBuffer bbepoch = ByteBuffer.wrap(ackEpochPacket.getData());
    
    // 读取 follower 回复的 epoch 信息，如果和leader 相等则为-1，否则为 follower 旧的 epoch信息
    ss = new StateSummary(bbepoch.getInt(), ackEpochPacket.getZxid());
    
    // 阻塞当前，直到leader 收到大多数的 follower 回复ack 信息
    leader.waitForEpochAck(this.getSid(), ss);
}
```

Leader#waitForEpochAck 

```

// 这个方法在leader 发送给follower new epoch 消息之后，处理follower 回复的 ack 消息
public void waitForEpochAck(long id, StateSummary ss) throws IOException, InterruptedException {
    synchronized(electingFollowers) {
        if (electionFinished) {
            return;
        }
        
        // -1 说明 follower 的 epoch 和leader 相等
        if (ss.getCurrentEpoch() != -1) {
            if (ss.isMoreRecentThan(leaderStateSummary)) {
                throw new IOException("Follower is ahead of the leader, leader summary: " 
                                                + leaderStateSummary.getCurrentEpoch()
                                                + " (current epoch), "
                                                + leaderStateSummary.getLastZxid()
                                                + " (last zxid)");
            }
            if (isParticipant(id)) {
                
                // 收到确认的follower 
                electingFollowers.add(id);
            }
        }
        QuorumVerifier verifier = self.getQuorumVerifier();
        
        // 判断是否已经有大多数follower 回复了确认信息
        if (electingFollowers.contains(self.getId()) && verifier.containsQuorum(electingFollowers)) {
            electionFinished = true;
            electingFollowers.notifyAll();
        } else {
            long start = Time.currentElapsedTime();
            long cur = start;
            long end = start + self.getInitLimit()*self.getTickTime();
            
            // 阻塞等待
            while(!electionFinished && cur < end) {
                electingFollowers.wait(end - cur);
                cur = Time.currentElapsedTime();
            }
            if (!electionFinished) {
                throw new InterruptedException("Timeout while waiting for epoch to be acked by quorum");
            }
        }
    }
}
```

根据follower返回的 lastLoggedZxid，确认follower 的数据同步策略
```
// follower 返回自身的 lastLoggedZxid
peerLastZxid = ss.getLastZxid();

// Take any necessary action if we need to send TRUNC or DIFF
// startForwarding() will be called in all cases

boolean needSnap = syncFollower(peerLastZxid, leader.zk.getZKDatabase(), leader);
```

LearnerHandler#syncFollower
```
public boolean syncFollower(long peerLastZxid, ZKDatabase db, Leader leader) {
    /*
     * When leader election is completed, the leader will set its
     * lastProcessedZxid to be (epoch < 32). There will be no txn associated
     * with this zxid.
     *
     * The learner will set its lastProcessedZxid to the same value if
     * it get DIFF or SNAP from the leader. If the same learner come
     * back to sync with leader using this zxid, we will never find this
     * zxid in our history. In this case, we will ignore TRUNC logic and
     * always send DIFF if we have old enough history
     */
    
    // 当选举完成后，leader 设置 lastProcessedZxid 为(根据newEpoch 计算的zxid)。zxid 不涉及任何事务。
    // Learner 收到 leader 发送的 DIFF or SNAP 时会将 lastProcessedZxid 设置为zxid。
    // 如果Learner使用这个zxid和 leader 同步是，将会在历史记录中永远也找不到这个zxid的值。
    // 这种情况下，如果我们有足够的历史记录那么我们将会忽略TRUNC逻辑而发送DIFF
    boolean isPeerNewEpochZxid = (peerLastZxid & 0xffffffffL) == 0;
    // Keep track of the latest zxid which already queued
    long currentZxid = peerLastZxid;
    boolean needSnap = true;
    
    // 事务日志同步是否开启
    boolean txnLogSyncEnabled = db.isTxnLogSyncEnabled();
    ReentrantReadWriteLock lock = db.getLogLock();
    ReadLock rl = lock.readLock();
    try {
        rl.lock();
        
        // 获取 leader db的最大最小提交事务id
        long maxCommittedLog = db.getmaxCommittedLog();
        long minCommittedLog = db.getminCommittedLog();
        
        // 最后处理的zxid
        long lastProcessedZxid = db.getDataTreeLastProcessedZxid();

        LOG.info("Synchronizing with Follower sid: {} maxCommittedLog=0x{}"
                + " minCommittedLog=0x{} lastProcessedZxid=0x{}"
                + " peerLastZxid=0x{}", getSid(),
                Long.toHexString(maxCommittedLog),
                Long.toHexString(minCommittedLog),
                Long.toHexString(lastProcessedZxid),
                Long.toHexString(peerLastZxid));
        
        // 如果没有提交日志
        if (db.getCommittedLog().isEmpty()) {
            /*
             * It is possible that committedLog is empty. In that case
             * setting these value to the latest txn in leader db
             * will reduce the case that we need to handle
             *
             * Here is how each case handle by the if block below
             * 1. lastProcessZxid == peerZxid -> Handle by (2)
             * 2. lastProcessZxid < peerZxid -> Handle by (3)
             * 3. lastProcessZxid > peerZxid -> Handle by (5)
             */
            minCommittedLog = lastProcessedZxid;
            maxCommittedLog = lastProcessedZxid;
        }

        /*
         * Here are the cases that we want to handle
         *
         * 1. Force sending snapshot (for testing purpose)
         * 2. Peer and leader is already sync, send empty diff
         * 3. Follower has txn that we haven't seen. This may be old leader
         *    so we need to send TRUNC. However, if peer has newEpochZxid,
         *    we cannot send TRUNC since the follower has no txnlog
         * 4. Follower is within committedLog range or already in-sync.
         *    We may need to send DIFF or TRUNC depending on follower's zxid
         *    We always send empty DIFF if follower is already in-sync
         * 5. Follower missed the committedLog. We will try to use on-disk
         *    txnlog + committedLog to sync with follower. If that fail,
         *    we will send snapshot
         */
        // 处理的5种方式
        // 1、强制发送snapshot(测试目的)
        // 2、follower 和 leader 已经同步，则发送空的 diff 消息
        // 3、follower 的 txn 比leader 要多，那么则发送 TRUNC ，回滚follower多余的 txn数据。
        // 4、follower 在committedLog同步的范围内，那么根据follower 的zxid来决定发送 TRUNC 还是DIFF,如果follower 在同步中的话就发送 空的DIFF
        // 5、follower 丢失了committedLog。会将leader 磁盘上的txnLog和committedLog同步给follower，如果失败了，会发送snapshot
        
        // 是否强制同步snapshot
        if (forceSnapSync) {
            // Force leader to use snapshot to sync with follower
            LOG.warn("Forcing snapshot sync - should not see this in production");
        } else if (lastProcessedZxid == peerLastZxid) {
            // Follower is already sync with us, send empty diff
            LOG.info("Sending DIFF zxid=0x" + Long.toHexString(peerLastZxid) +
                     " for peer sid: " +  getSid());
            
            // 如果 follower 的peerLastZxid 和 leader lastProcessedZxid相等，则进行增量同步
            queueOpPacket(Leader.DIFF, peerLastZxid);
            needOpPacket = false;
            needSnap = false;
            
        } else if (peerLastZxid > maxCommittedLog && !isPeerNewEpochZxid) {
        
            // Newer than committedLog, send trunc and done
            LOG.debug("Sending TRUNC to follower zxidToSend=0x" +
                      Long.toHexString(maxCommittedLog) +
                      " for peer sid:" +  getSid());
            
            // 如果follower 数据比leader 要新，则需要回滚到leader的 maxCommittedLog 
            queueOpPacket(Leader.TRUNC, maxCommittedLog);
            currentZxid = maxCommittedLog;
            needOpPacket = false;
            needSnap = false;
        } else if ((maxCommittedLog >= peerLastZxid)
                && (minCommittedLog <= peerLastZxid)) {
            // Follower is within commitLog range
            
            // follower 数据在处于最大最小事务之间，则增量同步 
            LOG.info("Using committedLog for peer sid: " +  getSid());
            Iterator<Proposal> itr = db.getCommittedLog().iterator();
            currentZxid = queueCommittedProposals(itr, peerLastZxid,
                                                 null, maxCommittedLog);
            
            // 不需要同步整个snapshot 
            needSnap = false;
        } else if (peerLastZxid < minCommittedLog && txnLogSyncEnabled) {
            // 如果follower 最大的zxid 小于 leader 最小 minCommittedLog，并且允许从txnLog中同步数据
            
            // Use txnlog and committedLog to sync
            
            // Calculate sizeLimit that we allow to retrieve txnlog from disk
            // 计算事务日志的大小(允许我们从磁盘中恢复的事务日志大小)
            long sizeLimit = db.calculateTxnLogSizeLimit();
            // This method can return empty iterator if the requested zxid
            // is older than on-disk txnlog
            // 如果 zxid > txnlogId 返回空
            Iterator<Proposal> txnLogItr = db.getProposalsFromTxnLog(
                    peerLastZxid, sizeLimit);
            if (txnLogItr.hasNext()) {
                LOG.info("Use txnlog and committedLog for peer sid: " +  getSid());
                
                // 首先从txnlog 中恢复数据
                currentZxid = queueCommittedProposals(txnLogItr, peerLastZxid,
                                                     minCommittedLog, maxCommittedLog);

                LOG.debug("Queueing committedLog 0x" + Long.toHexString(currentZxid));
                
                // 再从committedLog中恢复数据
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
        LOG.debug("Start forwarding 0x" + Long.toHexString(currentZxid) +
                  " for peer sid: " +  getSid());
        
        // follower 开始同步数据
        leaderLastZxid = leader.startForwarding(this, currentZxid);
    } finally {
        rl.unlock();
    }

    // committedLog 丢失(即follower的 zxid 小于磁盘上txnLogId )，同步snapshot
    if (needOpPacket && !needSnap) {
        // This should never happen, but we should fall back to sending
        // snapshot just in case.
        LOG.error("Unhandled scenario for peer sid: " +  getSid() +
                 " fall back to use snapshot");
        needSnap = true;
    }

    return needSnap;
}
```

发送同步数据包到对应的follower
```
if (getVersion() < 0x10000) {
    QuorumPacket newLeaderQP = new QuorumPacket(Leader.NEWLEADER,
            newLeaderZxid, null, null);
    oa.writeRecord(newLeaderQP, "packet");
} else {
    
    // 版本信息默认为 0x10000 ，添加 Leader.NEWLEADER 数据包到queuedPackets
    QuorumPacket newLeaderQP = new QuorumPacket(Leader.NEWLEADER,
            newLeaderZxid, leader.self.getLastSeenQuorumVerifier()
                    .toString().getBytes(), null);
    queuedPackets.add(newLeaderQP);
}
bufferedOutput.flush();


/* if we are not truncating or sending a diff just send a snapshot */
// 同步snapshot 
if (needSnap) {
    boolean exemptFromThrottle = getLearnerType() != LearnerType.OBSERVER;
    
    // 构建一个snapshot
    LearnerSnapshot snapshot = 
            leader.getLearnerSnapshotThrottler().beginSnapshot(exemptFromThrottle);
    try {
        long zxidToSend = leader.zk.getZKDatabase().getDataTreeLastProcessedZxid();
        
        // 发送Leader.SNAP 数据包给follower ，代表接下来要同步snapshot
        oa.writeRecord(new QuorumPacket(Leader.SNAP, zxidToSend, null, null), "packet");
        bufferedOutput.flush();

        LOG.info("Sending snapshot last zxid of peer is 0x{}, zxid of leader is 0x{}, "
                + "send zxid of db as 0x{}, {} concurrent snapshots, " 
                + "snapshot was {} from throttle",
                Long.toHexString(peerLastZxid), 
                Long.toHexString(leaderLastZxid),
                Long.toHexString(zxidToSend), 
                snapshot.getConcurrentSnapshotNumber(),
                snapshot.isEssential() ? "exempt" : "not exempt");
        // Dump data to peer
        leader.zk.getZKDatabase().serializeSnapshot(oa);
        oa.writeString("BenWasHere", "signature");
        bufferedOutput.flush();
    } finally {
        snapshot.close();
    }
}

```

开启线程进行同步数据

```$xslt


// Start thread that blast packets in the queue to learner
startSendingPackets();

// 可以看出startSendingPackets 就是开启了一个线程，然后调用 sendPackets 来发送 queuedPackets 队列中的数据包

private void sendPackets() throws InterruptedException {
    long traceMask = ZooTrace.SERVER_PACKET_TRACE_MASK;
    while (true) {
        try {
            QuorumPacket p;
            
            // 从发送队列中取出要发送的数据包
            p = queuedPackets.poll();
            if (p == null) {
                bufferedOutput.flush();
                
                // 如果为空，则阻塞
                p = queuedPackets.take();
            }
            
            if (p == proposalOfDeath) {
                // Packet of death!
                break;
            }
            if (p.getType() == Leader.PING) {
                traceMask = ZooTrace.SERVER_PING_TRACE_MASK;
            }
            if (p.getType() == Leader.PROPOSAL) {
                syncLimitCheck.updateProposal(p.getZxid(), System.nanoTime());
            }
            if (LOG.isTraceEnabled()) {
                ZooTrace.logQuorumPacket(LOG, traceMask, 'o', p);
            }
            
            // 发送数据给follower
            oa.writeRecord(p, "packet");
        } catch (IOException e) {
            if (!sock.isClosed()) {
                LOG.warn("Unexpected exception at " + this, e);
                try {
                    // this will cause everything to shutdown on
                    // this learner handler and will help notify
                    // the learner/observer instantaneously
                    sock.close();
                } catch(IOException ie) {
                    LOG.warn("Error closing socket for handler " + this, ie);
                }
            }
            break;
        }
    }
}

```








    

Leader#getEpochToPropose 判断是否已经有大多数的Follower已经连接到Leader

```
// 这个方法只有两处调用：
// 1、Leader#lead -> Leader#getEpochToPropose 
// 2、LeaderHandler#run -> Leader#getEpochToPropose 这个主要是处理每一个follower 连接

public long getEpochToPropose(long sid, long lastAcceptedEpoch) throws InterruptedException, IOException {
    synchronized(connectingFollowers) {
        if (!waitingForNewEpoch) {
            return epoch;
        }
        if (lastAcceptedEpoch >= epoch) {
            epoch = lastAcceptedEpoch+1;
        }
        
        // 有投票权连接
        if (isParticipant(sid)) {
            connectingFollowers.add(sid);
        }
        QuorumVerifier verifier = self.getQuorumVerifier();
        if (connectingFollowers.contains(self.getId()) &&
                                        
                                        // connectingFollowers 已经有过半连接
                                        verifier.containsQuorum(connectingFollowers)) {
            waitingForNewEpoch = false;
            self.setAcceptedEpoch(epoch);
            connectingFollowers.notifyAll();
        } else {
            // 如果连接不过半，则阻塞继续等待新的连接
            long start = Time.currentElapsedTime();
            long cur = start;
            long end = start + self.getInitLimit()*self.getTickTime();
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






## Follower 初始化

Follower的继承关系：Follower -> Learner

```
case FOLLOWING:
    try {
       LOG.info("FOLLOWING");
       
        // 构建 Follower
        setFollower(makeFollower(logFactory));
       
        // followLeader，当前线程阻塞
        follower.followLeader();
    } catch (Exception e) {
        // 如果 follower 和 leader 连接断开，则会抛出IOException
       LOG.warn("Unexpected exception",e);
    } finally {
       // 出现异常，重新选举
       follower.shutdown();
       setFollower(null);
       updateServerState();
    }
    break;
```

### Follower#followLeader

```
void followLeader() throws InterruptedException {
    self.end_fle = Time.currentElapsedTime();
    long electionTimeTaken = self.end_fle - self.start_fle;
    self.setElectionTimeTaken(electionTimeTaken);
    LOG.info("FOLLOWING - LEADER ELECTION TOOK - {} {}", electionTimeTaken,
            QuorumPeer.FLE_TIME_UNIT);
    self.start_fle = 0;
    self.end_fle = 0;
    fzk.registerJMX(new FollowerBean(this, zk), self.jmxLocalPeerBean);
    try {
        
        // 找出leader 
        QuorumServer leaderServer = findLeader();            
        try {
            
            // 连接到leader，可能会出现io 异常
            connectToLeader(leaderServer.addr, leaderServer.hostname);
            
            // 连接到leader，同步选举的轮次
            long newEpochZxid = registerWithLeader(Leader.FOLLOWERINFO);
            if (self.isReconfigStateChange())
               throw new Exception("learned about role change");
            //check to see if the leader zxid is lower than ours
            //this should never happen but is just a safety check
            
            // 再次确认 epoch 
            long newEpoch = ZxidUtils.getEpochFromZxid(newEpochZxid);
            if (newEpoch < self.getAcceptedEpoch()) {
                LOG.error("Proposed leader epoch " + ZxidUtils.zxidToString(newEpochZxid)
                        + " is less than our accepted epoch " + ZxidUtils.zxidToString(self.getAcceptedEpoch()));
                throw new IOException("Error: Epoch of leader is lower");
            }
            
            // 同步leader 数据
            syncWithLeader(newEpochZxid);                
            QuorumPacket qp = new QuorumPacket();
            
            // 这里会循环的处理从leader 读取的数据
            while (this.isRunning()) {
                readPacket(qp);
                // 处理从leader读取到的数据
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

### Leader#registerWithLeader

```
// Follower 或 Observer 调用
protected long registerWithLeader(int pktType) throws IOException{
    /*
     * Send follower info, including last zxid and sid
     */
    long lastLoggedZxid = self.getLastLoggedZxid();
    QuorumPacket qp = new QuorumPacket();                
    qp.setType(pktType);
    qp.setZxid(ZxidUtils.makeZxid(self.getAcceptedEpoch(), 0));
    
    /*
     * Add sid to payload
     */
    
    // 构造 QuorumPacket 中的data数据，包含sid，protocolVersion，configVersion
    LearnerInfo li = new LearnerInfo(self.getId(), 0x10000, self.getQuorumVerifier().getVersion());
    ByteArrayOutputStream bsid = new ByteArrayOutputStream();
    BinaryOutputArchive boa = BinaryOutputArchive.getArchive(bsid);
    boa.writeRecord(li, "LearnerInfo");
    qp.setData(bsid.toByteArray());
    
    // 发送QuorumPacket 给leader，对应参考 LearnerHandler 的部分
    writePacket(qp, true);
    
    // 读取 leader 的回传数据
    readPacket(qp);
    
    // 从leader 的回传数据解析出最新的选举轮次 epoch ，这个时候leader 一定是接受到了大多数的 Follower 连接，并对每个 follower的epoch进行了处理，计算出最新的epoch        
    final long newEpoch = ZxidUtils.getEpochFromZxid(qp.getZxid());
    
    // leader 回传的类型，所以下面只看这部分代码
    if (qp.getType() == Leader.LEADERINFO) {
        
        // we are connected to a 1.0 server so accept the new epoch and read the next packet
        // 读取 ProtocolVersion ，默认为0x10000
        leaderProtocolVersion = ByteBuffer.wrap(qp.getData()).getInt();
        byte epochBytes[] = new byte[4];
        final ByteBuffer wrappedEpochBytes = ByteBuffer.wrap(epochBytes);
        
        // 如果leader 的选举轮次大于当前节点的选举轮次，则更新选举轮次
        if (newEpoch > self.getAcceptedEpoch()) {
            
            // 再将当前的选举轮次发送给leader
            wrappedEpochBytes.putInt((int)self.getCurrentEpoch());
            self.setAcceptedEpoch(newEpoch);
        } else if (newEpoch == self.getAcceptedEpoch()) {
            // since we have already acked an epoch equal to the leaders, we cannot ack
            // again, but we still need to send our lastZxid to the leader so that we can
            // sync with it if it does assume leadership of the epoch.
            // the -1 indicates that this reply should not count as an ack for the new epoch
            
            // -1 代表当前的回复不应该作为一个new epoch的 ack 数量
            wrappedEpochBytes.putInt(-1);
        } else {
            throw new IOException("Leaders epoch, " + newEpoch + " is less than accepted epoch, " + self.getAcceptedEpoch());
        }
        
        // 向leader 发送 ack new epoch，并且将follower 的lastLoggedZxid回复给leader，以便leader判断当前follower数据同步的方式
        QuorumPacket ackNewEpoch = new QuorumPacket(Leader.ACKEPOCH, lastLoggedZxid, epochBytes, null);
        writePacket(ackNewEpoch, true);
        
        // 发送完后返回最新的zxid 
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

### 同步 Leader 数据

```

protected void syncWithLeader(long newLeaderZxid) throws Exception{
    
    // 构造ACK消息报
    QuorumPacket ack = new QuorumPacket(Leader.ACK, 0, null, null);
    QuorumPacket qp = new QuorumPacket();
    long newEpoch = ZxidUtils.getEpochFromZxid(newLeaderZxid);
    
    QuorumVerifier newLeaderQV = null;
    
    // In the DIFF case we don't need to do a snapshot because the transactions will sync on top of any existing snapshot
    // For SNAP and TRUNC the snapshot is needed to save that history
    // 增量模式下，不需要同步snapshot。
    boolean snapshotNeeded = true;
    
    // 读取leader 数据
    readPacket(qp);
    LinkedList<Long> packetsCommitted = new LinkedList<Long>();
    LinkedList<PacketInFlight> packetsNotCommitted = new LinkedList<PacketInFlight>();
    
    // 根据leader 反馈的同步数据方式进行数据同步，
    synchronized (zk) {
        
        // DIFF 当前follower 和leader 数据相同
        if (qp.getType() == Leader.DIFF) {
            LOG.info("Getting a diff from the leader 0x{}", Long.toHexString(qp.getZxid()));
            snapshotNeeded = false;
        }
        else if (qp.getType() == Leader.SNAP) {
            LOG.info("Getting a snapshot from leader 0x" + Long.toHexString(qp.getZxid()));
            // The leader is going to dump the database
            // db is clear as part of deserializeSnapshot()
            
            // 首先会清空当前节点的数据库，然后用leader的数据初始化新的dataTree
            zk.getZKDatabase().deserializeSnapshot(leaderIs);
            // ZOOKEEPER-2819: overwrite config node content extracted
            // from leader snapshot with local config, to avoid potential
            // inconsistency of config node content during rolling restart.
            if (!QuorumPeerConfig.isReconfigEnabled()) {
                LOG.debug("Reset config node content from local config after deserialization of snapshot.");
                zk.getZKDatabase().initConfigInZKDatabase(self.getQuorumVerifier());
            }
            String signature = leaderIs.readString("signature");
            if (!signature.equals("BenWasHere")) {
                LOG.error("Missing signature. Got " + signature);
                throw new IOException("Missing signature");                   
            }
            zk.getZKDatabase().setlastProcessedZxid(qp.getZxid());
        } else if (qp.getType() == Leader.TRUNC) {
            //we need to truncate the log to the lastzxid of the leader
            LOG.warn("Truncating log to get in sync with the leader 0x"
                    + Long.toHexString(qp.getZxid()));
                    
            // 将follower的数据回滚到 leader#maxCommittedLog 
            boolean truncated=zk.getZKDatabase().truncateLog(qp.getZxid());
            if (!truncated) {
                // not able to truncate the log
                LOG.error("Not able to truncate the log "
                        + Long.toHexString(qp.getZxid()));
                System.exit(13);
            }
            zk.getZKDatabase().setlastProcessedZxid(qp.getZxid());

        }
        else {
            LOG.error("Got unexpected packet from leader: {}, exiting ... ",
                      LearnerHandler.packetToString(qp));
            System.exit(13);

        }
        zk.getZKDatabase().initConfigInZKDatabase(self.getQuorumVerifier());
        
        // 创建session 过期检查线程
        zk.createSessionTracker();            
        
        long lastQueued = 0;

        // 在Zab V1.0 (ZK 3.4+)版本中，follower收到 NEWLEADER 消息后会做一次 snapshot，但是在V1.0以前，在收到UPDATE消息后也会做snapshot。
        // 但是在 V1.0 中，在 收到 NEWLEADER消息后还会再次收到 UPDATE的消息，这个标志位就是确保在V1.0版本不会做两次snapshot。
        boolean isPreZAB1_0 = true;
        
        // 如果没有从leader 同步 snapshot，确保事务不会只存在内存中，而是要写到 transaction log
        boolean writeToTxnLog = !snapshotNeeded;
        // we are now going to start getting transactions to apply followed by an UPTODATE
        
        // 
        outerLoop:
        while (self.isRunning()) {
            readPacket(qp);
            switch(qp.getType()) {
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
                   QuorumVerifier qv = self.configFromString(new String(setDataTxn.getData()));
                   self.setLastSeenQuorumVerifier(qv, true);                               
                }
                
                // 添加 PROPOSAL 消息 到 packetsNotCommitted 队列
                packetsNotCommitted.add(pif);
                break;
            case Leader.COMMIT:
            case Leader.COMMITANDACTIVATE:
                
                // 取出第一条 PROPOSAL 消息，目的是按顺序提交消息 
                // 发送消息顺序 p1,p2,p3,c1,p4,c2,c3,c4 ... 提交也是按照p1,p2,p3,p4 这个顺序提交
                pif = packetsNotCommitted.peekFirst();
                // Leader 发送的提交消息
                if (pif.hdr.getZxid() == qp.getZxid() && qp.getType() == Leader.COMMITANDACTIVATE) {
                    
                    // 处理配置信息
                    QuorumVerifier qv = self.configFromString(new String(((SetDataTxn) pif.rec).getData()));
                    boolean majorChange = self.processReconfig(qv, ByteBuffer.wrap(qp.getData()).getLong(),
                            qp.getZxid(), true);
                    if (majorChange) {
                        throw new Exception("changes proposed in reconfig");
                    }
                }
                
                // 是否写 transaction log
                if (!writeToTxnLog) {
                    if (pif.hdr.getZxid() != qp.getZxid()) {
                        LOG.warn("Committing " + qp.getZxid() + ", but next proposal is " + pif.hdr.getZxid());
                    } else {
                        
                        // 写日志
                        zk.processTxn(pif.hdr, pif.rec);
                        // 写完日志后将数据包从 packetsNotCommitted 移出
                        packetsNotCommitted.remove();
                    }
                } else {
                    // 
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
                
                // 如果leader 发送Leader.NEWLEADER数据包，则回复ACK
                writePacket(new QuorumPacket(Leader.ACK, newLeaderZxid, null, null), true);
                break;
            }
        }
    }
    ack.setZxid(ZxidUtils.makeZxid(newEpoch, 0));
    writePacket(ack, true);
    sock.setSoTimeout(self.tickTime * self.syncLimit);
    zk.startup();
    /*
     * Update the election vote here to ensure that all members of the
     * ensemble report the same vote to new servers that start up and
     * send leader election notifications to the ensemble.
     * 
     * @see https://issues.apache.org/jira/browse/ZOOKEEPER-1732
     */
    self.updateElectionVote(newEpoch);

    // We need to log the stuff that came in between the snapshot and the uptodate
    if (zk instanceof FollowerZooKeeperServer) {
        FollowerZooKeeperServer fzk = (FollowerZooKeeperServer)zk;
        for(PacketInFlight p: packetsNotCommitted) {
            fzk.logRequest(p.hdr, p.rec);
        }
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

