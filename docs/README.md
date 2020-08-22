# jdk1.8-source-analysis 《优质JDK源码分析文章集锦》

收集整理网上优质的关于JDK源码分析文章，帮助大家在学习相关的源码上，减少知识的不关联性。

- 文章申明：引用文章将注明出处，原作者。

# 导入源码过程中的注意事项

JDK1.8对应JDK版本下载：[JDK1.8](https://pan.baidu.com/s/1KYYnDt4iHXjvUO_xAy8fKg) 提取码：49wi

源码在src目录下

以下两个类手动添加的，解决编译过程中该包的丢失

sun.font.FontConfigManager 

sun.awt.UNIXToolkit 

其中：
   1.请手动添加jdk下面的lib到path中
   
   2.lib文件夹下面的junit测试jar包添加到path中，以便编写测试用例

# 学习计划

很多java开发的小伙伴都会阅读jdk源码，然而确不知道应该从哪读起。以下为小编整理的通常所需阅读的源码范围。
标题为包名，后面序号为优先级1-4，优先级递减

1、java.lang
```
1) Object 1
2) String 1
3) AbstractStringBuilder 1
4) StringBuffer 1
5) StringBuilder 1
6) Boolean 2
7) Byte 2
8) Double 2
9) Float 2
10) Integer 2
11) Long 2
12) Short 2
13) Thread 2
14) ThreadLocal 2
15) Enum 3
16) Throwable 3
17) Error 3
18) Exception 3
19) Class 4
20) ClassLoader 4
21) Compiler 4
22) System 4
23) Package 4
24) Void 4
```
2、java.util
```
1) AbstractList 1
2) AbstractMap 1
3) AbstractSet 1
4) ArrayList 1
5) LinkedList 1
6) HashMap 1
7) Hashtable 1
8) HashSet 1
9) LinkedHashMap 1
10) LinkedHashSet 1
11) TreeMap 1
12) TreeSet 1
13) Vector 2
14) Queue 2
15) Stack 2
16) SortedMap 2
17) SortedSet 2
18) Collections 3
19) Arrays 3
20) Comparator 3
21) Iterator 3
22) Base64 4
23) Date 4
24) EventListener 4
25) Random 4
26) SubList 4
27) Timer 4
28) UUID 4
29) WeakHashMap 4
```

3、java.util.concurrent
```
1) ConcurrentHashMap 1
2) Executor 2
3) AbstractExecutorService 2
4) ExecutorService 2
5) ThreadPoolExecutor 2
6) BlockingQueue 2
7）AbstractQueuedSynchronizer 2
8）CountDownLatch 2
9) FutureTask 2
10）Semaphore 2
11）CyclicBarrier 2
13）CopyOnWriteArrayList 3
14）SynchronousQueue 3
15）BlockingDeque 3
16) Callable 4
```

4、java.util.concurrent.atomic
```
1) AtomicBoolean 2
2) AtomicInteger 2
3) AtomicLong 2
4) AtomicReference 3
```

5、java.lang.reflect
```
1) Field 2
2) Method 2
```

6、java.lang.annotation
```
1) Annotation 3
2) Target 3
3) Inherited 3
4) Retention 3
5) Documented 4
6) ElementType 4
7) Native 4
8) Repeatable 4
```

7、java.util.concurrent.locks
```
1) Lock 2
2) Condition 2
3) ReentrantLock 2
4) ReentrantReadWriteLock 2
```

8、java.io
```
1) File 3
2) InputStream   3
3) OutputStream  3
4) Reader  4
5) Writer  4
```

9、java.nio
```
1) Buffer 3
2) ByteBuffer 4
3) CharBuffer 4
4) DoubleBuffer 4
5) FloatBuffer 4
6) IntBuffer 4
7) LongBuffer 4
8) ShortBuffer 4
```

10、java.sql
```
1) Connection 3
2) Driver 3
3) DriverManager 3
4) JDBCType 3
5) ResultSet 4
6) Statement 4
```

11、java.net
```
1) Socket 3
2) ServerSocket 3
3) URI 4
4) URL 4
5) URLEncoder 4
```

# JDK1.8源码分析系列文章
01=JDK1.8源码分析01之学习建议（可以延伸其他源码学习）

02=JDK1.8源码分析02之阅读源码顺序

03=JDK1.8源码分析03之idea搭建源码阅读环境

04=JDK1.8源码分析04之java.lang.Object类

# JDK相关面试集锦系列文章

持续更新中.......

# 目录结构：
```metadata json
├── docs 项目文档
├── lib
├── openjdk jdk8的openjdk源码
├── out
└── src jdk8源码
```

