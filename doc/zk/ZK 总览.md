# ZK 总结

## ZK 启动

ZK 的启动入口为QuorumPeerMain#main方法，如果没有配置启动参数或配置了多个启动参数都会以单机的方式启动ZK，
只有args.length == 1 && config.isDistributed() 情形下才会以集群的方式启动。启动过程中会启动一个定时清理任务。

### 从配置文件启动ZK


