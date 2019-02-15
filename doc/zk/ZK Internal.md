# ZooKeeper Internals

##  Introduction

## Atomic Broadcast

ZooKeeper 的核心就是一个保持所有服务同步的原子消息系统。

### Guarantees, Properties, and Definitions

ZooKeeper 使用了消息系统提供特定保证，如下：

可靠性传递（Reliable delivery）：

如果一个消息m，被传递给一个服务，那么它会被传递给所有的服务。

总体有序（Total order）：

如果一个消息a在消息b之前被传递给一个服务，那么在所有的服务中a都会在b之前。

因果顺序（Causal order）：

如果发送者b在发送消息a后发送了消息b，那么a一定在b之前。如果发送者a在发送消息b之后发送消息c，那么c一定在b之后。









