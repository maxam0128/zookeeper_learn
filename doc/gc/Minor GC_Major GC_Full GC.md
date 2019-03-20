# Minor GC vs Major GC vs Full GC

## Minor GC

Young Space 区的GC称之为 Minor GC。下面是触发 Minor GC的一些事件：

- 1、Minor GC 通常是由于 JVM 已经没有足够的空间为新对象分配内存而引起的。通常是由于Eden 区空间已满引起的。
- 2、在Minor GC 期间，老年代通常是被忽略的。但是来自 Olg gen 对于 Young gen 的引用通常是被作为GC ROOT(Old gen 的 card table)，在标记阶段会忽略 Young gen 对 Old gen 的引用。
- 3、Minor GC 是会 stop-the-world 的。但是对于大多数应用来说，如果 eden gen 的 大多数对象都会被回收而不是copy 到 Survivor/Old 区的话，这个时间都是可以忽略的。

## Major GC vs Full GC

- Major GC : Old gen 区的 GC
- Full GC ： 整个堆的GC(包括 young 和 old). 

