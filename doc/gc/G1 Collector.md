# G1

G1 设计的一个关键点就是GC过程中的STW是可预测和可配置的。所以说G1 是一个软实时的垃圾收集器，你可以设置指定的性能指标(可以设置 STW 的时间在一个范围区间)。
可参考：[美团G1](https://tech.meituan.com/2016/09/23/g1.html)


## G1 重要概念

### Region 
G1 的堆不再被分成联系的young 和 old区，而是被分成2048 Region，每个Region都可能是 Eden,Survivor 或 old。
将所有 eden 和 Survivor 组成一个逻辑上的young Generation,所有的 old 组成一个old Generation。
每个Region的大小可通过 -XX:G1HeapRegionSize 配置，取值范围从1M到32M，且是2的指数。如果不设定，那么G1会根据Heap大小自动决定。
Region标明了H，它代表Humongous，这表示这些Region存储的是巨大对象（humongous object，H-obj），即大小大于等于region一半的对象。

### SATB

全称是Snapshot-At-The-Beginning。是GC开始时活着的对象的一个快照。根据三色标记算法，我们知道对象存在三种状态：
* 白：对象没有被标记到，标记阶段结束后，会被当做垃圾回收掉。
* 灰：对象被标记了，但是它的field还没有被标记或标记完。 
* 黑：对象被标记了，且它的所有field也被标记完了。

### RSET

全称是Remembered Set，是辅助GC过程的一种结构，典型的空间换时间工具，和Card Table有些类似。
还有一种数据结构也是辅助GC的：Collection Set（CSet），它记录了GC要收集的Region集合，集合里的Region可以是任意年代的。
在GC的时候，对于 old->young和 old->old 的跨代对象引用，只要扫描对应的CSet中的RSet即可。 
逻辑上说每个Region都有一个RSet，RSet记录了其他Region中的对象引用本Region中对象的关系，属于points-into结构（谁引用了我的对象）。
而Card Table则是一种points-out（我引用了谁的对象）的结构，每个Card 覆盖一定范围的Heap（一般为512Bytes）。
G1的RSet是在Card Table的基础上实现的：每个Region会记录下别的Region有指向自己的指针，并标记这些指针分别在哪些Card的范围内。 
这个RSet其实是一个Hash Table，Key是别的Region的起始地址，Value是一个集合，里面的元素是Card Table的Index。

在做YGC的时候，只需要选定young generation region的RSet作为根集，
这些RSet记录了old->young的跨代引用，避免了扫描整个old generation。 
而mixed gc的时候，old generation中记录了old->old的RSet，
young->old的引用由扫描全部young generation region得到，这样也不用扫描全部old generation region。所以RSet的引入大大减少了GC的工作量。

## Evacuation Pause: Fully Young

在应用的开始阶段，G1是没有任何额外的信息，所以这时是 fully-young 模式。 当 young Generation 需要快要满时，应用线程会停止，所有活的对象会被复制到Survivor，所以任务空闲的region都有可能成为 Survivor区。

这种处理copy的过程就叫 Evacuation，这跟我们之前看到的其他young 区的收集器是一样的。 

## G1 GC模式   

G1提供了两种GC模式，Young GC和Mixed GC，两种都是完全Stop The World的。 
* Young GC：选定所有年轻代里的Region。通过控制年轻代的region个数，即年轻代内存大小，来控制young GC的时间开销。 
* Mixed GC：选定所有年轻代里的Region，外加根据global concurrent marking统计得出收集收益高的若干老年代Region。在用户指定的开销目标范围内尽可能选择收益高的老年代Region。

由上面的描述可知，Mixed GC不是full GC，它只能回收部分老年代的Region，如果mixed GC实在无法跟上程序分配内存的速度，导致老年代填满无法继续进行Mixed GC，就会使用serial old GC（full GC）来收集整个GC heap。所以我们可以知道，G1是不提供full GC的。

上文中，多次提到了global concurrent marking，它的执行过程类似CMS，但是不同的是，在G1 GC中，它主要是为Mixed GC提供标记服务的，并不是一次GC过程的一个必须环节。global concurrent marking的执行过程分为四个步骤： 

* 初始标记（initial mark，STW）。它标记了从GC Root开始直接可达的对象。 
* 并发标记（Concurrent Marking）。这个阶段从GC Root开始对heap中的对象标记，标记线程与应用程序线程并行执行，并且收集各个Region的存活对象信息。 
* 最终标记（Remark，STW）。标记那些在并发标记阶段发生变化的对象，将被回收。 
* 清除垃圾（Cleanup）。清除空Region（没有存活对象的），加入到free list。

第一阶段initial mark是共用了Young GC的暂停，这是因为他们可以复用root scan操作，所以可以说global concurrent marking是伴随Young GC而发生的。第四阶段Cleanup只是回收了没有存活对象的Region，所以它并不需要STW。

Young GC发生的时机大家都知道，那什么时候发生Mixed GC呢？其实是由一些参数控制着的，另外也控制着哪些老年代Region会被选入CSet。 
* G1HeapWastePercent：在global concurrent marking结束之后，我们可以知道old gen regions中有多少空间要被回收，在每次YGC之后和再次发生Mixed GC之前，会检查垃圾占比是否达到此参数，只有达到了，下次才会发生Mixed GC。 
* G1MixedGCLiveThresholdPercent：old generation region中的存活对象的占比，只有在此参数之下，才会被选入CSet。 
* G1MixedGCCountTarget：一次global concurrent marking之后，最多执行Mixed GC的次数。 
* G1OldCSetRegionThresholdPercent：一次Mixed GC中能被选入CSet的最多old generation region数量。





