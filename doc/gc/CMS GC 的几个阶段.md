# CMS 

## 概述

CMS(Concurrent Mark Sweep) 收集器它是一个老年代的回收器。

### Major GC和Full GC

参考R大[解答](https://www.zhihu.com/question/41922036/answer/93079526).

- young GC：当young gen中的eden区分配满的时候触发。注意young GC中有部分存活对象会晋升到old gen，所以young GC后old gen的占用量通常会有所升高。
- full GC：当准备要触发一次young GC时，如果发现统计数据说之前young GC的平均晋升大小比目前old gen剩余的空间大，
则不会触发young GC而是转为触发full GC（因为HotSpot VM的GC里，除了CMS的concurrent collection之外，其它能收集old gen的GC都会同时收集整个GC堆，包括young gen，所以不需要事先触发一次单独的young GC）；
或者，如果有perm gen的话，要在perm gen分配空间但已经没有足够空间时，也要触发一次full GC；或者System.gc()、heap dump带GC，默认也是触发full GC。

所以应用出现fullGC的原因点：
1、young gc后年轻代平均晋升的对象大小大于到 old 剩余空间
2、永久带空间不足
3、显式调用 System.gc()、heap dump带GC、jcmd <pid> GC.run


### CMS GC 触发条件 

- 1、CMS GC 的触发通过 -XX:+UseCMSInitiatingOccupancyOnly 和 XX:CMSInitiatingOccupancyFraction=75 (默认为68)触发，
也就是说如果没有配置这两个参数，在老年代使用空间达到68%时会触发一次CMS GC。

- 2、老年代的可用空间大于YGC 平均晋升到老年代对象的大小

- 3、设置了 -XX:+CMSClassUnloadingEnabled 且 永久代的内存使用率达到了 CMSInitiatingPermOccupancyFraction (默认为92)



### CMS 收集器和G1收集器的选择

经过测试，在一般情况下，堆空间小于4G是，CMS收集器的性能要比G1好。因为CMS收集器使用的算法比G1简单，因此在比较简单的环境中，它云溪的更快。
但是在使用大堆或巨型堆是，由于G1收集器可以分割工作，通常它比CMS收集器表现更好。

在回收任何对象之前，CMS收集器都必须扫描完这个老年代空间。显然扫描完整的堆的时间跟堆的大小密切相关。如果CMS发生 Concurrent Mode Failure，则会退化成串行的FullGC。

另外，CMS使用的标记清除算法，所以它会使堆碎片化。这个可以通过下面的参数避免：

- XX:CMSFullGCsBeforeCompaction : 运行多少次GC后进行一次压缩
- XX+UseCMSCompactAtFullCollection: full gc 时对old gen 进行压缩

## CMS 收集器的几个阶段

[原文链接](https://blogs.oracle.com/jonthecollector/the-unspoken-phases-of-cms)

### 1、Initial mark

CMS 收集的开始阶段，这个阶段是stop-the-world，即应用的所有线程都会停止，CMS 会扫描应用线程栈上的对象引用(即查找GC ROOT，这个时候 young -> old 引用也是作为 gc root的)。
GC ROOT：

### 2、Concurrent mark

并发标记阶段，这个阶段CMS会重启所有的应用线程，并且顺着GC ROOT，找出所有的活的对象。

### 3、Concurrent PreCleaning phase

并发预清理的阶段。这个阶段主要是修正上一个阶段应用对一些对象引用的改变(如果对象的引用的改变，那么它会被标记为 dirty)，这些对象会被标记为 live(这里可能会有误判)。
并发执行阶段。这个阶段是个优化，当CMS在做并发标记时，应用线程也在运行，它可能会改变对象的引用。CMS 需要找出这些改变，然后重新作出标记，所以precleaning 阶段也是这样。CMS在应用线程改变对象引用时会使用一种方式来记录它。预清理看起来会记录这些变动并且标记存货的对象。例如如果线程AA持有一个对象XX的引用，但是它把这个引用传递给了线程BB，那么CMS需要知道对于线程BB对象XX是活的，即使AA已经不再持有XX的引用了。

### 4、The remark phase

重标记阶段是stop-the-world的。如果CMS和应用线程并发执行，并且应用线程会持续对活的对象做出改变，那么CMS不能准确的决定哪个对象是活的。所以CMS在重标记阶段会会停止所有应用线程以便它可以将应用线程所有的变动做出标记。
这个阶段的目标是标记出old gen 所有活的对象。

### 5、The sweep phase

这是可以并发的阶段，CMS 会查找所有的对象并把最近已死的对象添加它的freelists中。CMS 会分配一个freelist，在清除阶段这些freelist会被重复使用。

### 6、The reset phase

并发执行阶段。CMS 清除它的状态位以便下次回收。


