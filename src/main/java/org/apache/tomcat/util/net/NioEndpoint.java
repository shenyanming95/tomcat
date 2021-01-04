/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.jsse.JSSESupport;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.nio.channels.NetworkChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 *
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public class NioEndpoint extends AbstractJsseEndpoint<NioChannel,SocketChannel> {


    // -------------------------------------------------------------- Constants


    private static final Log log = LogFactory.getLog(NioEndpoint.class);


    public static final int OP_REGISTER = 0x100; //register interest op

    // ----------------------------------------------------------------- Fields

    private NioSelectorPool selectorPool = new NioSelectorPool();

    /**
     * Server socket "pointer".
     */
    private volatile ServerSocketChannel serverSock = null;

    /**
     * Stop latch used to wait for poller stop
     */
    private volatile CountDownLatch stopLatch = null;

    /**
     * Cache for poller events
     */
    private SynchronizedStack<PollerEvent> eventCache;

    /**
     * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL holds four)
     */
    private SynchronizedStack<NioChannel> nioChannels;


    // ------------------------------------------------------------- Properties


    /**
     * Generic properties, introspected
     */
    @Override
    public boolean setProperty(String name, String value) {
        final String selectorPoolName = "selectorPool.";
        try {
            if (name.startsWith(selectorPoolName)) {
                return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
            } else {
                return super.setProperty(name, value);
            }
        } catch (Exception e) {
            log.error(sm.getString("endpoint.setAttributeError", name, value), e);
            return false;
        }
    }


    /**
     * Use System.inheritableChannel to obtain channel from stdin/stdout.
     */
    private boolean useInheritedChannel = false;
    public void setUseInheritedChannel(boolean useInheritedChannel) { this.useInheritedChannel = useInheritedChannel; }
    public boolean getUseInheritedChannel() { return useInheritedChannel; }

    /**
     * Priority of the poller threads.
     */
    private int pollerThreadPriority = Thread.NORM_PRIORITY;
    public void setPollerThreadPriority(int pollerThreadPriority) { this.pollerThreadPriority = pollerThreadPriority; }
    public int getPollerThreadPriority() { return pollerThreadPriority; }


    /**
     * NO-OP.
     *
     * @param pollerThreadCount Unused
     *
     * @deprecated Will be removed in Tomcat 10.
     */
    @Deprecated
    public void setPollerThreadCount(int pollerThreadCount) { }
    /**
     * Always returns 1.
     *
     * @return Always 1.
     *
     * @deprecated Will be removed in Tomcat 10.
     */
    @Deprecated
    public int getPollerThreadCount() { return 1; }

    private long selectorTimeout = 1000;
    public void setSelectorTimeout(long timeout) { this.selectorTimeout = timeout;}
    public long getSelectorTimeout() { return this.selectorTimeout; }

    /**
     * The socket poller.
     */
    private Poller poller = null;


    public void setSelectorPool(NioSelectorPool selectorPool) {
        this.selectorPool = selectorPool;
    }

    /**
     * Is deferAccept supported?
     */
    @Override
    public boolean getDeferAccept() {
        // Not supported
        return false;
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Number of keep-alive sockets.
     *
     * @return The number of sockets currently in the keep-alive state waiting
     *         for the next request to be received on the socket
     */
    public int getKeepAliveCount() {
        if (poller == null) {
            return 0;
        } else {
            return poller.getKeyCount();
        }
    }


    // ----------------------------------------------- Public Lifecycle Methods

    /**
     * Initialize the endpoint.
     */
    @Override
    public void bind() throws Exception {
        // 初始化底层的 ServerSocket
        initServerSocket();

        // 设置 CountDownLatch, 用来等待 poller 停止
        setStopLatch(new CountDownLatch(1));

        // 初始化SSL(如果启用了SSL)
        initialiseSsl();

        // 开启 Selector Pool
        selectorPool.open(getName());
    }

    /**
     * 绑定并配置 ServerSocket 对象
     */
    protected void initServerSocket() throws Exception {
        if (!getUseInheritedChannel()) {
            // 自己创建ServerSocket
            serverSock = ServerSocketChannel.open();
            // 设置 java.net.ServerSocket 的属性
            socketProperties.setProperties(serverSock.socket());
            // 绑定主机地址和端口号, 默认为：0.0.0.0/0.0.0.0:8080
            InetSocketAddress addr = new InetSocketAddress(getAddress(), getPortWithOffset());
            // 绑定ServerSocket, 然后会设置与该Socket的请求连接队列的最大数量, 默认为100.
            serverSock.socket().bind(addr,getAcceptCount());
        } else {
            // 通过从 OS 的标准输入输出拿到Channel
            Channel ic = System.inheritedChannel();
            if (ic instanceof ServerSocketChannel) {
                serverSock = (ServerSocketChannel) ic;
            }
            if (serverSock == null) {
                throw new IllegalArgumentException(sm.getString("endpoint.init.bind.inherited"));
            }
        }
        // 配置 java.nio.channels.ServerSocketChannel 为阻塞模式, 当没有连接到来时, 它不会立即返回null, 而是阻塞住
        serverSock.configureBlocking(true); //mimic APR behavior
    }


    /**
     * 启动 NioEndpoint, 同时会创建{@link Acceptor} 和 {@link Poller}
     */
    @Override
    public void startInternal() throws Exception {
        if (!running) {
            running = true;
            paused = false;

            if (socketProperties.getProcessorCache() != 0) {
                // SocketProcessor 的缓存
                processorCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE, socketProperties.getProcessorCache());
            }
            if (socketProperties.getEventCache() != 0) {
                // PollerEvent 的缓存
                eventCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE, socketProperties.getEventCache());
            }
            if (socketProperties.getBufferPool() != 0) {
                // NioChannel 的缓存
                nioChannels = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE, socketProperties.getBufferPool());
            }

            // 创建线程池, 可以称为 worker 线程, 实际在Endpoint中处理清理的线程
            if (getExecutor() == null) {
                createExecutor();
            }

            // 设置 connectionLimitLatch, 表示最大连接数
            initializeConnectionLatch();

            // 启动 Poller, 它检测Channel中的I/O事件, 可读时, 创建SocketProcessor任务交给线程池
            poller = new Poller();
            Thread pollerThread = new Thread(poller, getName() + "-ClientPoller");
            pollerThread.setPriority(threadPriority);
            pollerThread.setDaemon(true);
            pollerThread.start();

            // 启动 Acceptor, 它会监听连接请求, 将Channel交给若干个Poller中的一个
            startAcceptorThread();
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
    @Override
    public void stopInternal() {
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            if (poller != null) {
                poller.destroy();
                poller = null;
            }
            try {
                if (!getStopLatch().await(selectorTimeout + 100, TimeUnit.MILLISECONDS)) {
                    log.warn(sm.getString("endpoint.nio.stopLatchAwaitFail"));
                }
            } catch (InterruptedException e) {
                log.warn(sm.getString("endpoint.nio.stopLatchAwaitInterrupted"), e);
            }
            shutdownExecutor();
            if (eventCache != null) {
                eventCache.clear();
                eventCache = null;
            }
            if (nioChannels != null) {
                nioChannels.clear();
                nioChannels = null;
            }
            if (processorCache != null) {
                processorCache.clear();
                processorCache = null;
            }
        }
    }


    /**
     * Deallocate NIO memory pools, and close server socket.
     */
    @Override
    public void unbind() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Destroy initiated for " +
                    new InetSocketAddress(getAddress(),getPortWithOffset()));
        }
        if (running) {
            stop();
        }
        try {
            doCloseServerSocket();
        } catch (IOException ioe) {
            getLog().warn(sm.getString("endpoint.serverSocket.closeFailed", getName()), ioe);
        }
        destroySsl();
        super.unbind();
        if (getHandler() != null ) {
            getHandler().recycle();
        }
        selectorPool.close();
        if (log.isDebugEnabled()) {
            log.debug("Destroy completed for " +
                    new InetSocketAddress(getAddress(), getPortWithOffset()));
        }
    }


    @Override
    protected void doCloseServerSocket() throws IOException {
        if (!getUseInheritedChannel() && serverSock != null) {
            // Close server socket
            serverSock.close();
        }
        serverSock = null;
    }

    // ------------------------------------------------------ Protected Methods

    protected NioSelectorPool getSelectorPool() {
        return selectorPool;
    }


    protected SynchronizedStack<NioChannel> getNioChannels() {
        return nioChannels;
    }


    protected Poller getPoller() {
        return poller;
    }


    protected CountDownLatch getStopLatch() {
        return stopLatch;
    }


    protected void setStopLatch(CountDownLatch stopLatch) {
        this.stopLatch = stopLatch;
    }


    /**
     * Process the specified connection.
     * @param socket The socket channel
     * @return <code>true</code> if the socket was correctly configured
     *  and processing may continue, <code>false</code> if the socket needs to be
     *  close immediately
     */
    @Override
    protected boolean setSocketOptions(SocketChannel socket) {
        NioSocketWrapper socketWrapper = null;
        try {
            // 这边是Tomcat对象池的设计, 优先取出之前创建好的NioChannel对象
            NioChannel channel = null;
            if (nioChannels != null) {
                channel = nioChannels.pop();
            }
            // 当然如果Tomcat刚启动, 这里面肯定是没有缓存的, 所以需要手动创建一个
            if (channel == null) {
                // 这边会根据配置, 创建一个 SocketBufferHandler,
                // SocketBufferHandler里面会分配 java.nio.ByteBuffer, 并且指定是要用堆内存还是直接内存创建缓冲区.
                SocketBufferHandler bufhandler = new SocketBufferHandler(
                        socketProperties.getAppReadBufSize(), // 默认8192字节, 即8kb
                        socketProperties.getAppWriteBufSize(), // 默认8192字节, 即8kb
                        socketProperties.getDirectBuffer()); //默认false, 即使用堆内存创建缓冲区
                if (isSSLEnabled()) {
                    // 如果是支持ssl, 创建 SecureNioChannel
                    channel = new SecureNioChannel(bufhandler, selectorPool, this);
                } else {
                    // 普通类型, 创建 NioChannel
                    channel = new NioChannel(bufhandler);
                }
            }
            // 这边就是一堆封装了, 然后上面说过, tomcat会复用 NioChannel, 所以这里会对它进行重置
            NioSocketWrapper newWrapper = new NioSocketWrapper(channel, this);
            channel.reset(socket, newWrapper);
            // Endpoint抽象父类有个 connections, 是个Map, 用于关联 Socket 和 NioSocketWrapper对象.
            connections.put(socket, newWrapper);
            socketWrapper = newWrapper;

            // Set socket properties
            // Disable blocking, polling will be used
            socket.configureBlocking(false);
            // 设置 Socket 的一些属性, 具体看里面实现
            socketProperties.setProperties(socket.socket());
            socketWrapper.setReadTimeout(getConnectionTimeout());
            socketWrapper.setWriteTimeout(getConnectionTimeout());
            socketWrapper.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            // 注册到 Poller 上
            poller.register(channel, socketWrapper);
            return true;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error(sm.getString("endpoint.socketOptionsError"), t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(tt);
            }
            if (socketWrapper == null) {
                destroySocket(socket);
            }
        }
        // Tell to close the socket if needed
        return false;
    }


    @Override
    protected void destroySocket(SocketChannel socket) {
        countDownConnection();
        try {
            socket.close();
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.err.close"), ioe);
            }
        }
    }


    @Override
    protected NetworkChannel getServerSocket() {
        return serverSock;
    }


    @Override
    protected SocketChannel serverSocketAccept() throws Exception {
        return serverSock.accept();
    }


    @Override
    protected Log getLog() {
        return log;
    }


    @Override
    protected SocketProcessorBase<NioChannel> createSocketProcessor(
            SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
        return new SocketProcessor(socketWrapper, event);
    }

    // ----------------------------------------------------- Poller Inner Classes

    /**
     * PollerEvent, cacheable object for poller events to avoid GC
     */
    public static class PollerEvent {

        private NioChannel socket;
        private int interestOps;

        public PollerEvent(NioChannel ch, int intOps) {
            reset(ch, intOps);
        }

        public void reset(NioChannel ch, int intOps) {
            socket = ch;
            interestOps = intOps;
        }

        public NioChannel getSocket() {
            return socket;
        }

        public int getInterestOps() {
            return interestOps;
        }

        public void reset() {
            reset(null, 0);
        }

        @Override
        public String toString() {
            return "Poller event: socket [" + socket + "], socketWrapper [" + socket.getSocketWrapper() +
                    "], interestOps [" + interestOps + "]";
        }
    }

    /**
     * Poller class.
     */
    public class Poller implements Runnable {

        private Selector selector;
        private final SynchronizedQueue<PollerEvent> events = new SynchronizedQueue<>();

        private volatile boolean close = false;
        // Optimize expiration handling
        private long nextExpiration = 0;

        private AtomicLong wakeupCounter = new AtomicLong(0);

        private volatile int keyCount = 0;

        public Poller() throws IOException {
            this.selector = Selector.open();
        }

        public int getKeyCount() { return keyCount; }

        public Selector getSelector() { return selector; }

        /**
         * Destroy the poller.
         */
        protected void destroy() {
            // Wait for polltime before doing anything, so that the poller threads
            // exit, otherwise parallel closure of sockets which are still
            // in the poller can cause problems
            close = true;
            selector.wakeup();
        }

        private void addEvent(PollerEvent event) {
            // 事件入队
            events.offer(event);
            // 因为在Poller的run()方法中, 会将 wakeupCounter 置为-1, 如果++1后变为0
            // 说明需要唤醒Selector
            if (wakeupCounter.incrementAndGet() == 0) {
                selector.wakeup();
            }
        }

        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socketWrapper to add to the poller
         * @param interestOps Operations for which to register this socket with
         *                    the Poller
         */
        public void add(NioSocketWrapper socketWrapper, int interestOps) {
            PollerEvent r = null;
            if (eventCache != null) {
                r = eventCache.pop();
            }
            if (r == null) {
                r = new PollerEvent(socketWrapper.getSocket(), interestOps);
            } else {
                r.reset(socketWrapper.getSocket(), interestOps);
            }
            addEvent(r);
            if (close) {
                processSocket(socketWrapper, SocketEvent.STOP, false);
            }
        }

        /**
         * 处理Poller上的事件队列, 提取注册事件中的Channel, 注册到Selector上, 并设置
         * 刚兴趣的事件为可读. 这样这个Poller在运行期间, 就会从 Selector#select() 中
         * 获取到可读事件.
         *
         * @return <code>true</code> if some events were processed,
         *   <code>false</code> if queue was empty
         */
        public boolean events() {
            boolean result = false;
            // 取出事件
            PollerEvent pe = null;
            for (int i = 0, size = events.size(); i < size && (pe = events.poll()) != null; i++ ) {
                result = true;
                NioChannel channel = pe.getSocket();
                NioSocketWrapper socketWrapper = channel.getSocketWrapper();
                int interestOps = pe.getInterestOps();
                // 如果是注册事件, Acceptor会把获取到的客户端连接, 就以OP_REGISTER的形式注册到Poller上.
                if (interestOps == OP_REGISTER) {
                    try {
                        // 获取到 java.nio.channels.SocketChannel, 将其注册到Poller自己的 Selector 上
                        channel.getIOChannel().register(getSelector(), SelectionKey.OP_READ, socketWrapper);
                    } catch (Exception x) {
                        log.error(sm.getString("endpoint.nio.registerFail"), x);
                    }
                } else {
                    final SelectionKey key = channel.getIOChannel().keyFor(getSelector());
                    if (key == null) {
                        // The key was cancelled (e.g. due to socket closure)
                        // and removed from the selector while it was being
                        // processed. Count down the connections at this point
                        // since it won't have been counted down when the socket
                        // closed.
                        socketWrapper.close();
                    } else {
                        final NioSocketWrapper attachment = (NioSocketWrapper) key.attachment();
                        if (attachment != null) {
                            // We are registering the key to start with, reset the fairness counter.
                            try {
                                int ops = key.interestOps() | interestOps;
                                attachment.interestOps(ops);
                                key.interestOps(ops);
                            } catch (CancelledKeyException ckx) {
                                cancelledKey(key, socketWrapper);
                            }
                        } else {
                            cancelledKey(key, attachment);
                        }
                    }
                }
                if (running && !paused && eventCache != null) {
                    pe.reset();
                    eventCache.push(pe);
                }
            }

            return result;
        }

        /**
         * Registers a newly created socket with the poller.
         *
         * @param socket    The newly created socket
         * @param socketWrapper The socket wrapper
         */
        public void register(final NioChannel socket, final NioSocketWrapper socketWrapper) {
            socketWrapper.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.
            // 跟 NioChannel 设计一样, 会对 PollerEvent 进行对象缓存. 复用它
            PollerEvent event = null;
            if (eventCache != null) {
                event = eventCache.pop();
            }
            // 如果没有对象可以复用, 就创建新的; 不然就重置它, 注意这边塞的是 OP_REGISTER 事件
            if (event == null) {
                event = new PollerEvent(socket, OP_REGISTER);
            } else {
                event.reset(socket, OP_REGISTER);
            }
            // 添加到Poller的队列中
            addEvent(event);
        }

        public void cancelledKey(SelectionKey sk, SocketWrapperBase<NioChannel> socketWrapper) {
            try {
                // If is important to cancel the key first, otherwise a deadlock may occur between the
                // poller select and the socket channel close which would cancel the key
                if (sk != null) {
                    sk.attach(null);
                    if (sk.isValid()) {
                        sk.cancel();
                    }
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) {
                    log.error(sm.getString("endpoint.debug.channelCloseFail"), e);
                }
            } finally {
                if (socketWrapper != null) {
                    socketWrapper.close();
                }
            }
        }

        /**
         * The background thread that adds sockets to the Poller, checks the
         * poller for triggered events and hands the associated socket off to an
         * appropriate processor as events occur.
         */
        @Override
        public void run() {
            // 一直循环处理, 直至调用了destroy()
            while (true) {
                boolean hasEvents = false;
                try {
                    if (!close) {
                        // 为true的话表示处理了一些事件, 为false说明队列为空
                        hasEvents = events();
                        if (wakeupCounter.getAndSet(-1) > 0) {
                            // 将 wakeupCounter 的值置为-1, 同时返回旧值.
                            // 如果旧值大于0, 说明还有其它任务正在等待这个Selector, 这边就不会使用阻塞调用
                            keyCount = selector.selectNow();
                        } else {
                            // 如果没有任务等待在Selector上, 那就让它阻塞一会儿, 默认1000
                            keyCount = selector.select(selectorTimeout);
                        }
                        // 每一次循环, 重置为0
                        wakeupCounter.set(0);
                    }
                    if (close) {
                        // 如果poller关闭, 先处理剩余事件
                        events();
                        timeout(0, false);
                        try {
                            // 关闭selector
                            selector.close();
                        } catch (IOException ioe) {
                            log.error(sm.getString("endpoint.nio.selectorCloseFail"), ioe);
                        }
                        break;
                    }
                } catch (Throwable x) {
                    ExceptionUtils.handleThrowable(x);
                    log.error(sm.getString("endpoint.nio.selectorLoopError"), x);
                    continue;
                }
                // Either we timed out or we woke up, process events first
                if (keyCount == 0) {
                    hasEvents = (hasEvents | events());
                }
                // 类似自己使用Nio一样, 遍历Selector上的所有选择键SelectionKey, 一个一个处理
                Iterator<SelectionKey> iterator = keyCount > 0 ? selector.selectedKeys().iterator() : null;
                while (iterator != null && iterator.hasNext()) {
                    SelectionKey sk = iterator.next();
                    NioSocketWrapper socketWrapper = (NioSocketWrapper) sk.attachment();
                    // Attachment may be null if another thread has called
                    // cancelledKey()
                    iterator.remove();
                    if (socketWrapper != null) {
                        // 处理请求
                        processKey(sk, socketWrapper);
                    }
                }
                // Process timeouts
                timeout(keyCount,hasEvents);
            }
            getStopLatch().countDown();
        }

        /**
         *
         * 处理注册在 Poller 的 Selector 上的选择键
         * @param sk 选择键
         * @param socketWrapper 包裹的 NioChannel
         */
        protected void processKey(SelectionKey sk, NioSocketWrapper socketWrapper) {
            try {
                if (close) {
                    cancelledKey(sk, socketWrapper);
                } else if (sk.isValid() && socketWrapper != null) {
                    // 可读、可写事件
                    if (sk.isReadable() || sk.isWritable()) {
                        if (socketWrapper.getSendfileData() != null) {
                            processSendfile(sk, socketWrapper, false);
                        } else {
                            unreg(sk, socketWrapper, sk.readyOps());
                            boolean closeSocket = false;
                            // Read goes before write
                            if (sk.isReadable()) {
                                if (socketWrapper.readOperation != null) {
                                    if (!socketWrapper.readOperation.process()) {
                                        closeSocket = true;
                                    }
                                } else if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {
                                    closeSocket = true;
                                }
                            }
                            if (!closeSocket && sk.isWritable()) {
                                if (socketWrapper.writeOperation != null) {
                                    if (!socketWrapper.writeOperation.process()) {
                                        closeSocket = true;
                                    }
                                } else if (!processSocket(socketWrapper, SocketEvent.OPEN_WRITE, true)) {
                                    closeSocket = true;
                                }
                            }
                            if (closeSocket) {
                                cancelledKey(sk, socketWrapper);
                            }
                        }
                    }
                } else {
                    // Invalid key
                    cancelledKey(sk, socketWrapper);
                }
            } catch (CancelledKeyException ckx) {
                cancelledKey(sk, socketWrapper);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString("endpoint.nio.keyProcessingError"), t);
            }
        }

        public SendfileState processSendfile(SelectionKey sk, NioSocketWrapper socketWrapper,
                boolean calledByProcessor) {
            NioChannel sc = null;
            try {
                unreg(sk, socketWrapper, sk.readyOps());
                SendfileData sd = socketWrapper.getSendfileData();

                if (log.isTraceEnabled()) {
                    log.trace("Processing send file for: " + sd.fileName);
                }

                if (sd.fchannel == null) {
                    // Setup the file channel
                    File f = new File(sd.fileName);
                    @SuppressWarnings("resource") // Closed when channel is closed
                    FileInputStream fis = new FileInputStream(f);
                    sd.fchannel = fis.getChannel();
                }

                // Configure output channel
                sc = socketWrapper.getSocket();
                // TLS/SSL channel is slightly different
                WritableByteChannel wc = ((sc instanceof SecureNioChannel) ? sc : sc.getIOChannel());

                // We still have data in the buffer
                if (sc.getOutboundRemaining() > 0) {
                    if (sc.flushOutbound()) {
                        socketWrapper.updateLastWrite();
                    }
                } else {
                    long written = sd.fchannel.transferTo(sd.pos, sd.length, wc);
                    if (written > 0) {
                        sd.pos += written;
                        sd.length -= written;
                        socketWrapper.updateLastWrite();
                    } else {
                        // Unusual not to be able to transfer any bytes
                        // Check the length was set correctly
                        if (sd.fchannel.size() <= sd.pos) {
                            throw new IOException(sm.getString("endpoint.sendfile.tooMuchData"));
                        }
                    }
                }
                if (sd.length <= 0 && sc.getOutboundRemaining()<=0) {
                    if (log.isDebugEnabled()) {
                        log.debug("Send file complete for: " + sd.fileName);
                    }
                    socketWrapper.setSendfileData(null);
                    try {
                        sd.fchannel.close();
                    } catch (Exception ignore) {
                    }
                    // For calls from outside the Poller, the caller is
                    // responsible for registering the socket for the
                    // appropriate event(s) if sendfile completes.
                    if (!calledByProcessor) {
                        switch (sd.keepAliveState) {
                        case NONE: {
                            if (log.isDebugEnabled()) {
                                log.debug("Send file connection is being closed");
                            }
                            poller.cancelledKey(sk, socketWrapper);
                            break;
                        }
                        case PIPELINED: {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, processing pipe-lined data");
                            }
                            if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {
                                poller.cancelledKey(sk, socketWrapper);
                            }
                            break;
                        }
                        case OPEN: {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, registering back for OP_READ");
                            }
                            reg(sk, socketWrapper, SelectionKey.OP_READ);
                            break;
                        }
                        }
                    }
                    return SendfileState.DONE;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("OP_WRITE for sendfile: " + sd.fileName);
                    }
                    if (calledByProcessor) {
                        add(socketWrapper, SelectionKey.OP_WRITE);
                    } else {
                        reg(sk, socketWrapper, SelectionKey.OP_WRITE);
                    }
                    return SendfileState.PENDING;
                }
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Unable to complete sendfile request:", e);
                }
                if (!calledByProcessor && sc != null) {
                    poller.cancelledKey(sk, socketWrapper);
                }
                return SendfileState.ERROR;
            } catch (Throwable t) {
                log.error(sm.getString("endpoint.sendfile.error"), t);
                if (!calledByProcessor && sc != null) {
                    poller.cancelledKey(sk, socketWrapper);
                }
                return SendfileState.ERROR;
            }
        }

        protected void unreg(SelectionKey sk, NioSocketWrapper socketWrapper, int readyOps) {
            // This is a must, so that we don't have multiple threads messing with the socket
            reg(sk, socketWrapper, sk.interestOps() & (~readyOps));
        }

        protected void reg(SelectionKey sk, NioSocketWrapper socketWrapper, int intops) {
            sk.interestOps(intops);
            socketWrapper.interestOps(intops);
        }

        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            // This method is called on every loop of the Poller. Don't process
            // timeouts on every loop of the Poller since that would create too
            // much load and timeouts can afford to wait a few seconds.
            // However, do process timeouts if any of the following are true:
            // - the selector simply timed out (suggests there isn't much load)
            // - the nextExpiration time has passed
            // - the server socket is being closed
            if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
                return;
            }
            int keycount = 0;
            try {
                for (SelectionKey key : selector.keys()) {
                    keycount++;
                    try {
                        NioSocketWrapper socketWrapper = (NioSocketWrapper) key.attachment();
                        if (socketWrapper == null) {
                            // We don't support any keys without attachments
                            cancelledKey(key, null);
                        } else if (close) {
                            key.interestOps(0);
                            // Avoid duplicate stop calls
                            socketWrapper.interestOps(0);
                            cancelledKey(key, socketWrapper);
                        } else if ((socketWrapper.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ ||
                                  (socketWrapper.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                            boolean readTimeout = false;
                            boolean writeTimeout = false;
                            // Check for read timeout
                            if ((socketWrapper.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
                                long delta = now - socketWrapper.getLastRead();
                                long timeout = socketWrapper.getReadTimeout();
                                if (timeout > 0 && delta > timeout) {
                                    readTimeout = true;
                                }
                            }
                            // Check for write timeout
                            if (!readTimeout && (socketWrapper.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                                long delta = now - socketWrapper.getLastWrite();
                                long timeout = socketWrapper.getWriteTimeout();
                                if (timeout > 0 && delta > timeout) {
                                    writeTimeout = true;
                                }
                            }
                            if (readTimeout || writeTimeout) {
                                key.interestOps(0);
                                // Avoid duplicate timeout calls
                                socketWrapper.interestOps(0);
                                socketWrapper.setError(new SocketTimeoutException());
                                if (readTimeout && socketWrapper.readOperation != null) {
                                    if (!socketWrapper.readOperation.process()) {
                                        cancelledKey(key, socketWrapper);
                                    }
                                } else if (writeTimeout && socketWrapper.writeOperation != null) {
                                    if (!socketWrapper.writeOperation.process()) {
                                        cancelledKey(key, socketWrapper);
                                    }
                                } else if (!processSocket(socketWrapper, SocketEvent.ERROR, true)) {
                                    cancelledKey(key, socketWrapper);
                                }
                            }
                        }
                    } catch (CancelledKeyException ckx) {
                        cancelledKey(key, (NioSocketWrapper) key.attachment());
                    }
                }
            } catch (ConcurrentModificationException cme) {
                // See https://bz.apache.org/bugzilla/show_bug.cgi?id=57943
                log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
            }
            // For logging purposes only
            long prevExp = nextExpiration;
            nextExpiration = System.currentTimeMillis() +
                    socketProperties.getTimeoutInterval();
            if (log.isTraceEnabled()) {
                log.trace("timeout completed: keys processed=" + keycount +
                        "; now=" + now + "; nextExpiration=" + prevExp +
                        "; keyCount=" + keyCount + "; hasEvents=" + hasEvents +
                        "; eval=" + ((now < prevExp) && (keyCount>0 || hasEvents) && (!close) ));
            }

        }
    }

    // --------------------------------------------------- Socket Wrapper Class

    public static class NioSocketWrapper extends SocketWrapperBase<NioChannel> {

        private final NioSelectorPool pool;
        private final SynchronizedStack<NioChannel> nioChannels;
        private final Poller poller;

        private int interestOps = 0;
        private CountDownLatch readLatch = null;
        private CountDownLatch writeLatch = null;
        private volatile SendfileData sendfileData = null;
        private volatile long lastRead = System.currentTimeMillis();
        private volatile long lastWrite = lastRead;

        public NioSocketWrapper(NioChannel channel, NioEndpoint endpoint) {
            super(channel, endpoint);
            pool = endpoint.getSelectorPool();
            nioChannels = endpoint.getNioChannels();
            poller = endpoint.getPoller();
            socketBufferHandler = channel.getBufHandler();
        }

        public Poller getPoller() { return poller; }
        public int interestOps() { return interestOps; }
        public int interestOps(int ops) { this.interestOps  = ops; return ops; }
        public CountDownLatch getReadLatch() { return readLatch; }
        public CountDownLatch getWriteLatch() { return writeLatch; }
        protected CountDownLatch resetLatch(CountDownLatch latch) {
            if (latch == null || latch.getCount() == 0) {
                return null;
            } else {
                throw new IllegalStateException(sm.getString("endpoint.nio.latchMustBeZero"));
            }
        }
        public void resetReadLatch() { readLatch = resetLatch(readLatch); }
        public void resetWriteLatch() { writeLatch = resetLatch(writeLatch); }

        protected CountDownLatch startLatch(CountDownLatch latch, int cnt) {
            if (latch == null || latch.getCount() == 0) {
                return new CountDownLatch(cnt);
            } else {
                throw new IllegalStateException(sm.getString("endpoint.nio.latchMustBeZero"));
            }
        }
        public void startReadLatch(int cnt) { readLatch = startLatch(readLatch, cnt); }
        public void startWriteLatch(int cnt) { writeLatch = startLatch(writeLatch, cnt); }

        protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
            if (latch == null) {
                throw new IllegalStateException(sm.getString("endpoint.nio.nullLatch"));
            }
            // Note: While the return value is ignored if the latch does time
            //       out, logic further up the call stack will trigger a
            //       SocketTimeoutException
            latch.await(timeout, unit);
        }
        public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(readLatch, timeout, unit); }
        public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(writeLatch, timeout, unit); }

        public void setSendfileData(SendfileData sf) { this.sendfileData = sf;}
        public SendfileData getSendfileData() { return this.sendfileData; }

        public void updateLastWrite() { lastWrite = System.currentTimeMillis(); }
        public long getLastWrite() { return lastWrite; }
        public void updateLastRead() { lastRead = System.currentTimeMillis(); }
        public long getLastRead() { return lastRead; }

        @Override
        public boolean isReadyForRead() throws IOException {
            socketBufferHandler.configureReadBufferForRead();

            if (socketBufferHandler.getReadBuffer().remaining() > 0) {
                return true;
            }

            fillReadBuffer(false);

            boolean isReady = socketBufferHandler.getReadBuffer().position() > 0;
            return isReady;
        }


        @Override
        public int read(boolean block, byte[] b, int off, int len) throws IOException {
            int nRead = populateReadBuffer(b, off, len);
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }

            // Fill the read buffer as best we can.
            nRead = fillReadBuffer(block);
            updateLastRead();

            // Fill as much of the remaining byte array as possible with the
            // data that was just read
            if (nRead > 0) {
                socketBufferHandler.configureReadBufferForRead();
                nRead = Math.min(nRead, len);
                socketBufferHandler.getReadBuffer().get(b, off, nRead);
            }
            return nRead;
        }


        @Override
        public int read(boolean block, ByteBuffer to) throws IOException {
            int nRead = populateReadBuffer(to);
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }

            // The socket read buffer capacity is socket.appReadBufSize
            int limit = socketBufferHandler.getReadBuffer().capacity();
            if (to.remaining() >= limit) {
                to.limit(to.position() + limit);
                nRead = fillReadBuffer(block, to);
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + this + "], Read direct from socket: [" + nRead + "]");
                }
                updateLastRead();
            } else {
                // Fill the read buffer as best we can.
                nRead = fillReadBuffer(block);
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + this + "], Read into buffer: [" + nRead + "]");
                }
                updateLastRead();

                // Fill as much of the remaining byte array as possible with the
                // data that was just read
                if (nRead > 0) {
                    nRead = populateReadBuffer(to);
                }
            }
            return nRead;
        }


        @Override
        protected void doClose() {
            if (log.isDebugEnabled()) {
                log.debug("Calling [" + getEndpoint() + "].closeSocket([" + this + "])");
            }
            try {
                getEndpoint().connections.remove(getSocket().getIOChannel());
                if (getSocket().isOpen()) {
                    getSocket().close(true);
                }
                if (getEndpoint().running && !getEndpoint().paused) {
                    if (nioChannels == null || !nioChannels.push(getSocket())) {
                        getSocket().free();
                    }
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) {
                    log.error(sm.getString("endpoint.debug.channelCloseFail"), e);
                }
            } finally {
                socketBufferHandler = SocketBufferHandler.EMPTY;
                nonBlockingWriteBuffer.clear();
                reset(NioChannel.CLOSED_NIO_CHANNEL);
            }
            try {
                SendfileData data = getSendfileData();
                if (data != null && data.fchannel != null && data.fchannel.isOpen()) {
                    data.fchannel.close();
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) {
                    log.error(sm.getString("endpoint.sendfile.closeError"), e);
                }
            }
        }

        private int fillReadBuffer(boolean block) throws IOException {
            socketBufferHandler.configureReadBufferForWrite();
            return fillReadBuffer(block, socketBufferHandler.getReadBuffer());
        }


        private int fillReadBuffer(boolean block, ByteBuffer to) throws IOException {
            int nRead;
            NioChannel socket = getSocket();
            if (socket == NioChannel.CLOSED_NIO_CHANNEL) {
                throw new ClosedChannelException();
            }
            if (block) {
                Selector selector = null;
                try {
                    selector = pool.get();
                } catch (IOException x) {
                    // Ignore
                }
                try {
                    nRead = pool.read(to, socket, selector, getReadTimeout());
                } finally {
                    if (selector != null) {
                        pool.put(selector);
                    }
                }
            } else {
                nRead = socket.read(to);
                if (nRead == -1) {
                    throw new EOFException();
                }
            }
            return nRead;
        }


        @Override
        protected void doWrite(boolean block, ByteBuffer from) throws IOException {
            NioChannel socket = getSocket();
            if (socket == NioChannel.CLOSED_NIO_CHANNEL) {
                throw new ClosedChannelException();
            }
            if (block) {
                long writeTimeout = getWriteTimeout();
                Selector selector = null;
                try {
                    selector = pool.get();
                } catch (IOException x) {
                    // Ignore
                }
                try {
                    pool.write(from, socket, selector, writeTimeout);
                    // Make sure we are flushed
                    do {
                    } while (!socket.flush(true, selector, writeTimeout));
                } finally {
                    if (selector != null) {
                        pool.put(selector);
                    }
                }
                // If there is data left in the buffer the socket will be registered for
                // write further up the stack. This is to ensure the socket is only
                // registered for write once as both container and user code can trigger
                // write registration.
            } else {
                int n = 0;
                do {
                    n = socket.write(from);
                    if (n == -1) {
                        throw new EOFException();
                    }
                } while (n > 0 && from.hasRemaining());
            }
            updateLastWrite();
        }


        @Override
        public void registerReadInterest() {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.debug.registerRead", this));
            }
            getPoller().add(this, SelectionKey.OP_READ);
        }


        @Override
        public void registerWriteInterest() {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.debug.registerWrite", this));
            }
            getPoller().add(this, SelectionKey.OP_WRITE);
        }


        @Override
        public SendfileDataBase createSendfileData(String filename, long pos, long length) {
            return new SendfileData(filename, pos, length);
        }


        @Override
        public SendfileState processSendfile(SendfileDataBase sendfileData) {
            setSendfileData((SendfileData) sendfileData);
            SelectionKey key = getSocket().getIOChannel().keyFor(getPoller().getSelector());
            // Might as well do the first write on this thread
            return getPoller().processSendfile(key, this, true);
        }


        @Override
        protected void populateRemoteAddr() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getInetAddress();
                if (inetAddr != null) {
                    remoteAddr = inetAddr.getHostAddress();
                }
            }
        }


        @Override
        protected void populateRemoteHost() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getInetAddress();
                if (inetAddr != null) {
                    remoteHost = inetAddr.getHostName();
                    if (remoteAddr == null) {
                        remoteAddr = inetAddr.getHostAddress();
                    }
                }
            }
        }


        @Override
        protected void populateRemotePort() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                remotePort = sc.socket().getPort();
            }
        }


        @Override
        protected void populateLocalName() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getLocalAddress();
                if (inetAddr != null) {
                    localName = inetAddr.getHostName();
                }
            }
        }


        @Override
        protected void populateLocalAddr() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getLocalAddress();
                if (inetAddr != null) {
                    localAddr = inetAddr.getHostAddress();
                }
            }
        }


        @Override
        protected void populateLocalPort() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                localPort = sc.socket().getLocalPort();
            }
        }


        /**
         * {@inheritDoc}
         * @param clientCertProvider Ignored for this implementation
         */
        @Override
        public SSLSupport getSslSupport(String clientCertProvider) {
            if (getSocket() instanceof SecureNioChannel) {
                SecureNioChannel ch = (SecureNioChannel) getSocket();
                SSLEngine sslEngine = ch.getSslEngine();
                if (sslEngine != null) {
                    SSLSession session = sslEngine.getSession();
                    return ((NioEndpoint) getEndpoint()).getSslImplementation().getSSLSupport(session);
                }
            }
            return null;
        }


        @Override
        public void doClientAuth(SSLSupport sslSupport) throws IOException {
            SecureNioChannel sslChannel = (SecureNioChannel) getSocket();
            SSLEngine engine = sslChannel.getSslEngine();
            if (!engine.getNeedClientAuth()) {
                // Need to re-negotiate SSL connection
                engine.setNeedClientAuth(true);
                sslChannel.rehandshake(getEndpoint().getConnectionTimeout());
                ((JSSESupport) sslSupport).setSession(engine.getSession());
            }
        }


        @Override
        public void setAppReadBufHandler(ApplicationBufferHandler handler) {
            getSocket().setAppReadBufHandler(handler);
        }

        @Override
        protected <A> OperationState<A> newOperationState(boolean read,
                ByteBuffer[] buffers, int offset, int length,
                BlockingMode block, long timeout, TimeUnit unit, A attachment,
                CompletionCheck check, CompletionHandler<Long, ? super A> handler,
                Semaphore semaphore, VectoredIOCompletionHandler<A> completion) {
            return new NioOperationState<>(read, buffers, offset, length, block,
                    timeout, unit, attachment, check, handler, semaphore, completion);
        }

        private class NioOperationState<A> extends OperationState<A> {
            private volatile boolean inline = true;
            private NioOperationState(boolean read, ByteBuffer[] buffers, int offset, int length,
                    BlockingMode block, long timeout, TimeUnit unit, A attachment, CompletionCheck check,
                    CompletionHandler<Long, ? super A> handler, Semaphore semaphore,
                    VectoredIOCompletionHandler<A> completion) {
                super(read, buffers, offset, length, block,
                        timeout, unit, attachment, check, handler, semaphore, completion);
            }

            @Override
            protected boolean isInline() {
                return inline;
            }

            @Override
            public void run() {
                // Perform the IO operation
                // Called from the poller to continue the IO operation
                long nBytes = 0;
                if (getError() == null) {
                    try {
                        synchronized (this) {
                            if (!completionDone) {
                                // This filters out same notification until processing
                                // of the current one is done
                                if (log.isDebugEnabled()) {
                                    log.debug("Skip concurrent " + (read ? "read" : "write") + " notification");
                                }
                                return;
                            }
                            if (read) {
                                // Read from main buffer first
                                if (!socketBufferHandler.isReadBufferEmpty()) {
                                    // There is still data inside the main read buffer, it needs to be read first
                                    socketBufferHandler.configureReadBufferForRead();
                                    for (int i = 0; i < length && !socketBufferHandler.isReadBufferEmpty(); i++) {
                                        nBytes += transfer(socketBufferHandler.getReadBuffer(), buffers[offset + i]);
                                    }
                                }
                                if (nBytes == 0) {
                                    nBytes = getSocket().read(buffers, offset, length);
                                    updateLastRead();
                                }
                            } else {
                                boolean doWrite = true;
                                // Write from main buffer first
                                if (!socketBufferHandler.isWriteBufferEmpty()) {
                                    // There is still data inside the main write buffer, it needs to be written first
                                    socketBufferHandler.configureWriteBufferForRead();
                                    do {
                                        nBytes = getSocket().write(socketBufferHandler.getWriteBuffer());
                                    } while (!socketBufferHandler.isWriteBufferEmpty() && nBytes > 0);
                                    if (!socketBufferHandler.isWriteBufferEmpty()) {
                                        doWrite = false;
                                    }
                                    // Preserve a negative value since it is an error
                                    if (nBytes > 0) {
                                        nBytes = 0;
                                    }
                                }
                                if (doWrite) {
                                    long n = 0;
                                    do {
                                        n = getSocket().write(buffers, offset, length);
                                        if (n == -1) {
                                            nBytes = n;
                                        } else {
                                            nBytes += n;
                                        }
                                    } while (n > 0);
                                    updateLastWrite();
                                }
                            }
                            if (nBytes != 0 || !buffersArrayHasRemaining(buffers, offset, length)) {
                                completionDone = false;
                            }
                        }
                    } catch (IOException e) {
                        setError(e);
                    }
                }
                if (nBytes > 0 || (nBytes == 0 && !buffersArrayHasRemaining(buffers, offset, length))) {
                    // The bytes processed are only updated in the completion handler
                    completion.completed(Long.valueOf(nBytes), this);
                } else if (nBytes < 0 || getError() != null) {
                    IOException error = getError();
                    if (error == null) {
                        error = new EOFException();
                    }
                    completion.failed(error, this);
                } else {
                    // As soon as the operation uses the poller, it is no longer inline
                    inline = false;
                    if (read) {
                        registerReadInterest();
                    } else {
                        registerWriteInterest();
                    }
                }
            }

        }

    }


    // ---------------------------------------------- SocketProcessor Inner Class

    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    protected class SocketProcessor extends SocketProcessorBase<NioChannel> {

        public SocketProcessor(SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
            super(socketWrapper, event);
        }

        /**
         * 处理Socket数据
         */
        @Override
        protected void doRun() {
            // 获取Poller来处理Socket
            NioChannel socket = socketWrapper.getSocket();
            Poller poller = NioEndpoint.this.poller;
            if (poller == null) {
                socketWrapper.close();
                return;
            }
            try {
                int handshake = -1;
                try {
                    if (socket.isHandshakeComplete()) {
                        // 安全传输层协议（TLS）用于在两个通信应用程序之间提供保密性和数据完整性,
                        // 如果不是采用TLS协议, 也就不会使用 org.apache.tomcat.util.net.SecureNioChannel
                        // 这边就直接处理Socket
                        handshake = 0;
                    } else if (event == SocketEvent.STOP || event == SocketEvent.DISCONNECT ||
                            event == SocketEvent.ERROR) {
                        // 无法完成TLS握手, 值不变都为-1
                        handshake = -1;
                    } else {
                        // 执行SSL握手
                        handshake = socket.handshake(event == SocketEvent.OPEN_READ, event == SocketEvent.OPEN_WRITE);
                        // The handshake process reads/writes from/to the
                        // socket. status may therefore be OPEN_WRITE once
                        // the handshake completes. However, the handshake
                        // happens when the socket is opened so the status
                        // must always be OPEN_READ after it completes. It
                        // is OK to always set this as it is only used if
                        // the handshake completes.
                        event = SocketEvent.OPEN_READ;
                    }
                } catch (IOException x) {
                    handshake = -1;
                    if (log.isDebugEnabled()) log.debug("Error during SSL handshake",x);
                } catch (CancelledKeyException ckx) {
                    handshake = -1;
                }
                // 仅当 handshake == 0 为0, 表示TCP握手成功, 才可以继续读取Socket
                if (handshake == 0) {
                    SocketState state = SocketState.OPEN;
                    // 重点是这边, 开始处理读请求, 如果event为空, 默认执行读事件, 否则执行对应事件.
                    // 一般事件, 都是可读事件, 是在Poller中events()方法设置的.
                    if (event == null) {
                        state = getHandler().process(socketWrapper, SocketEvent.OPEN_READ);
                    } else {
                        state = getHandler().process(socketWrapper, event);
                    }
                    if (state == SocketState.CLOSED) {
                        poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()), socketWrapper);
                    }
                } else if (handshake == -1 ) {
                    // -1表示连接失败, 所以tomcat这边会处理连接失败的事件
                    getHandler().process(socketWrapper, SocketEvent.CONNECT_FAIL);
                    poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()), socketWrapper);
                } else if (handshake == SelectionKey.OP_READ){
                    socketWrapper.registerReadInterest();
                } else if (handshake == SelectionKey.OP_WRITE){
                    socketWrapper.registerWriteInterest();
                }
            } catch (CancelledKeyException cx) {
                poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()), socketWrapper);
            } catch (VirtualMachineError vme) {
                ExceptionUtils.handleThrowable(vme);
            } catch (Throwable t) {
                log.error(sm.getString("endpoint.processing.fail"), t);
                poller.cancelledKey(socket.getIOChannel().keyFor(poller.getSelector()), socketWrapper);
            } finally {
                socketWrapper = null;
                event = null;
                // 请求处理完成以后, 将 SocketProcessor 推入缓存, 供下次请求使用
                if (running && !paused && processorCache != null) {
                    processorCache.push(this);
                }
            }
        }
    }

    // ----------------------------------------------- SendfileData Inner Class

    /**
     * SendfileData class.
     */
    public static class SendfileData extends SendfileDataBase {

        public SendfileData(String filename, long pos, long length) {
            super(filename, pos, length);
        }

        protected volatile FileChannel fchannel;
    }
}
