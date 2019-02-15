# ZK选举核心类

对于zk集群来说，服务节点收到一个写请求后，follower会讲请求转发给leader。leader会以改进的类似两阶段提交的方式执行该请求，并将执行结果以事务的方式将状态广播给所有的follower。那么zk集群中的这个leader是如何选出来的呢，下面我们详细分析。

## 选举过程核心类

### 连接管理类--QuorumCnxManager 
这个类的主要作用实现对选举过程中TCP连接的管理。它对每个服务器节点都维护了一个连接。首先看下这个类的核心成员。

- Listener：监听选举的线程类。它的主要作用是监听选举端口。节点刚启动时它是阻塞状态。当收到请求后，处理对方节点发送过来的请求。
详细可以参考QuorumCnxManager#handleConnection方法实现。

- RecvWorker: 接受消息的线程类，它会为每一服务个节点都建立一个接受线程，如果连接过程断开，它会将自己从接受池中移除。从名字可以得知，这个线程主要是接受选举过程中其它节点发送的选举信息，然后把这些消息添加到接受队列中。

- SendWorker: 发送消息线程类。如果在连接断开，它会重新建立连接。会循环的发送消息发送队列中的内容。

下面是这个类中的几个重要属性：

```
// 发送节点的map，key = sid，v = SendWorker
final ConcurrentHashMap<Long, SendWorker> senderWorkerMap;
// key = sid，v = sendQueue(每个发送节点的队列) 
final ConcurrentHashMap<Long, ArrayBlockingQueue<ByteBuffer>> queueSendMap;
// key = sid，v = 最后一次发送的消息内容
final ConcurrentHashMap<Long, ByteBuffer> lastMessageSent;

// 消息接受队列，RecvWorker 将接受到的消息先放到此队列，然后才依次处理
public final ArrayBlockingQueue<Message> recvQueue;
```

### 选举类-- FastLeaderElection
Election 接口的一个实现，其它的实现还有AuthFastLeaderElection和LeaderElection，不过在在3.4.0之后这两个实现就已经被废弃了，所以我们就着重看下FastLeaderElection(ps:fast是相对于LeaderElection这个来说)的实现。首先还是看下这个类主要的内部成员。

- Messenger：它主要是message handler的一个多线程版本的实现。它主要由两个类组成实现：WorkReceiver 和 WorkSender。
	- WorkReceiver：处理接受到的线程类。从QuorumCnxManager#recvQueue中获取消息，并处理。和QuorumCnxManager#RecvWorker不同的是，它是处理接受的到的消息；QuorumCnxManager#RecvWorker只接受消息。
	- WorkSender：消息发送线程类。主要功能是将当前节点产生的消息放到QuorumCnxManager#queueSendMap中，随后由QuorumCnxManager#SendWorker完成消息发送。

- Notification：接受到选票信息类。它包括发送投票信息节点的zxid、electionEpoch、sid、state、peerEpoch等信息。然后当前节点根据发送者的状态更新信息做出对应的处理。

- ToSend：发送给给其他节点的投票信息。

FastLeaderElection中的一些重要属性说明：

```

// 连接管理器,也就是前面所说的
QuorumCnxManager manager;

//投票消息发送队列，所有的投票消息会先入队，然后在由WorkSender去完成发送
LinkedBlockingQueue<ToSend> sendqueue;
//接受消息队列，WorkReceiver接受到的消息。
LinkedBlockingQueue<Notification> recvqueue;
// 当前节点的逻辑时钟
volatile long logicalclock; /* Election instance */
// proposed信息
long proposedLeader;
long proposedZxid;
long proposedEpoch;

```













