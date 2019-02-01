
# GC 原因
Following is a table which describes each possible GC Cause. It is ordered by an occurrence frequency in an average application.

下表描述了每种GC的原因并按照应用平均发生的频次排序。

| GC 原因 | 描述 |
| -- | -- | 
| Allocation Failure | 由于 年轻代 的内存不足而导致内存分配失败；会引起一次Minor GC。在Linux系统中，如果没有足够的内存时，内核会通过 [mem_notify](https://lwn.net/Articles/267013/) 来通知JVM，这是JVM 会触发一次GC(full gc ?，这种场景是不是发生在堆空间会扩展的情形下，比如说只设置了堆的最大空间，然后应用在运行过程中自行扩展堆)。|
| GC Locker | 在所有线程离开JNI 区域时发生的GC。更多信息参考  [Java Native Interface documentation web site](https://docs.oracle.com/javase/8/docs/technotes/guides/jni/index.html)。当有线程进入JNI Critical region时，GC会被阻塞，当所有线程离开这个区域时，GC 才会开始 |
| G1 Evacuation Pause | 这个发生在G1 收集器中。它是复制所有一个区域活(young 区 和 young + Tenured -- 即mix 区 )的对象到另一个区域时发生；|
| G1 Humongous Allocation | 发生在G1 收集器中。当对象的大小大于 region 50%时，会在Humongous 中分配。对象会被分配到一个特殊的空间。无论如何，它引起的是一次正常的GC，为此对象获取更多的空间。 |
| CMS Initial Mark | CMS 的初始标记阶段，更多信息参考  [Phases of CMS](https://blogs.oracle.com/jonthecollector/the-unspoken-phases-of-cms) 。它也会触发young 区的收集。|
| System.gc() | 应用代码中显式调用System.gc()。可以通过 -XX:+DisableExplicitGC 参数禁止掉。|
| Adaptive Size Ergonomics | 表明当前使用的是堆的自适应策略(在运行期间改变young和Tenured 区的大小)。使用 -XX:+UseAdaptiveSizePolicy 参数开启，默认开启|
| Allocation Profiler | 这个只在 jdk 8 之前并且通过 -Xaprof 这个参数开启时才会发生。在JVM 退出之前发生。|
| Heap Inspection | 堆上的监控操作引发的GC，很大可能是由 jamp -histo:live 这个标志位引起的。|
| Heap Dump | heap dump 之前触发的GC(通过分析工具触发的 heap dump，如 jmap ). |
| Last Ditch Collection | jdk 8 以后，Metaspace 替代了 jdk 7以前的 PermGen。JVM 首先会触发相应的收集器尝试清理这个区域。如果没用的话，尝试扩展这个区域。如果还不起作用，那就会使用当前的原因触发 full gc。在这期间软引用会被清除。|
| Perm Generation Full | PermGen 分配失败后触发的GC，jdk 8 之前。|
| Metadata GC Threshold	 | Metaspace 分配失败之后触发的 GC。jdk 8 之后。|
| JvmtiEnv ForceGarbageCollection | 通过JVM tool interface 调用 ForceGarbageCollection 功能。|


