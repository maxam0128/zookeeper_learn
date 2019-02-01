# FollowerZooKeeperServer 

 前面已经分析过Follower 角色启动后的初始化流程了，接下来这一篇分析一下Follower 对于请求的处理。
 我们知道Follower在和Leader同步完数据(Follower#syncWithLeader)之后会启动FollowerZooKeeperServer，
 在FollowerZooKeeperServer启动过程中有一步很重要的步骤就是设置请求处理器(FollowerZooKeeperServer#setupRequestProcessors)，代码如下：
 
 ```
 
 // 下面即为请求处理链的构造
@Override
protected void setupRequestProcessors() {
    RequestProcessor finalProcessor = new FinalRequestProcessor(this);
    commitProcessor = new CommitProcessor(finalProcessor,
            Long.toString(getServerId()), true, getZooKeeperServerListener());
    commitProcessor.start();
    firstProcessor = new FollowerRequestProcessor(this, commitProcessor);
    ((FollowerRequestProcessor) firstProcessor).start();
    syncProcessor = new SyncRequestProcessor(this,
            new SendAckRequestProcessor((Learner)getFollower()));
    syncProcessor.start();
}
 ```
 

