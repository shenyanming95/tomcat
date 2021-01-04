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
import org.apache.tomcat.jni.Error;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;

/**
 * Acceptor 是Tomcat中用来接收连接的组件, 它会从 ServerSocket 中获取到 Socket,
 * 然后交由 Poller 去处理读请求.
 *
 * @param <U>
 */
public class Acceptor<U> implements Runnable {

    private static final Log log = LogFactory.getLog(Acceptor.class);
    private static final StringManager sm = StringManager.getManager(Acceptor.class);

    private static final int INITIAL_ERROR_DELAY = 50;
    private static final int MAX_ERROR_DELAY = 1600;

    /**
     * 为啥这边会引用 Endpoint？
     * 因为Tomcat支持多种I/O模式, nio、aio、apr, 而Acceptor是通用组件, 不管使用哪种I/O模型, 都会用到Acceptor来接收请求;
     * 但是, 每个I/O模型接收请求的方式不一样, 所以Tomcat将Endpoint引起来, 同时提供一个获取Socket的方法, 让Acceptor调用.
     * 这就是组合的魅力！
     */
    private final AbstractEndpoint<?,U> endpoint;


    private String threadName;
    protected volatile AcceptorState state = AcceptorState.NEW;

    public Acceptor(AbstractEndpoint<?,U> endpoint) {
        this.endpoint = endpoint;
    }

    public final AcceptorState getState() {
        return state;
    }

    final void setThreadName(final String threadName) {
        this.threadName = threadName;
    }

    final String getThreadName() {
        return threadName;
    }


    @Override
    public void run() {
        int errorDelay = 0;
        // Acceptor会一直在这里循环, 直到 Endpoint 被关闭.
        while (endpoint.isRunning()) {

            // 内层循环, endpoint处于运行, 并且处于暂停中, 为了避免一直死循环下去, 这边会进行一个小睡眠.
            // 其实自己在设计服务端程序时, 可以借鉴这样的做法.
            while (endpoint.isPaused() && endpoint.isRunning()) {
                state = AcceptorState.PAUSED;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
            // 有可能取消暂停了, 但是也取消运行了, 所以这边做个判断, endpoint停止后退出循序
            if (!endpoint.isRunning()) {
                break;
            }
            state = AcceptorState.RUNNING;
            try {
                // LimitLatch组件, 如果达到最大连接数, Acceptor会在这里阻塞, 等待之前连接释放.
                endpoint.countUpOrAwaitConnection();

                // 有可能 Acceptor 在达到最大连接数阻塞等待期间, 如果被停止了, 需要结束当次循环, 重新进入判断
                if (endpoint.isPaused()) {
                    continue;
                }

                U socket = null;
                try {
                    // 在这里等待客户端连接, 不同的I/O模型有不同的Socket实现.
                    socket = endpoint.serverSocketAccept();
                } catch (Exception ioe) {
                    // We didn't get a socket
                    endpoint.countDownConnection();
                    if (endpoint.isRunning()) {
                        // Introduce delay if necessary
                        errorDelay = handleExceptionWithDelay(errorDelay);
                        // re-throw
                        throw ioe;
                    } else {
                        break;
                    }
                }
                // Successful accept, reset the error delay
                errorDelay = 0;

                // 配置 Socket 对象
                if (endpoint.isRunning() && !endpoint.isPaused()) {
                    // 如果成功, setSocketOptions()会将Socket移交给适当的处理器. 注意：这个方法是异步的, 如果进入了Servlet容器, 那就会返回true
                    // 所以请求就是从这边开始处理的~
                    if (!endpoint.setSocketOptions(socket)) {
                        endpoint.closeSocket(socket);
                    }
                } else {
                    // Endpoint关闭, 会销毁这个Socket, 其实就会说把它关闭
                    endpoint.destroySocket(socket);
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                String msg = sm.getString("endpoint.accept.fail");
                // APR specific.
                // Could push this down but not sure it is worth the trouble.
                if (t instanceof Error) {
                    Error e = (Error) t;
                    if (e.getError() == 233) {
                        // Not an error on HP-UX so log as a warning
                        // so it can be filtered out on that platform
                        // See bug 50273
                        log.warn(msg, t);
                    } else {
                        log.error(msg, t);
                    }
                } else {
                        log.error(msg, t);
                }
            }
        }
        // 循环一旦退出, Acceptor就会被置为ENDED状态
        state = AcceptorState.ENDED;
    }


    /**
     * Handles exceptions where a delay is required to prevent a Thread from
     * entering a tight loop which will consume CPU and may also trigger large
     * amounts of logging. For example, this can happen if the ulimit for open
     * files is reached.
     *
     * @param currentErrorDelay The current delay being applied on failure
     * @return  The delay to apply on the next failure
     */
    protected int handleExceptionWithDelay(int currentErrorDelay) {
        // Don't delay on first exception
        if (currentErrorDelay > 0) {
            try {
                Thread.sleep(currentErrorDelay);
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        // On subsequent exceptions, start the delay at 50ms, doubling the delay
        // on every subsequent exception until the delay reaches 1.6 seconds.
        if (currentErrorDelay == 0) {
            return INITIAL_ERROR_DELAY;
        } else if (currentErrorDelay < MAX_ERROR_DELAY) {
            return currentErrorDelay * 2;
        } else {
            return MAX_ERROR_DELAY;
        }
    }


    public enum AcceptorState {
        NEW, RUNNING, PAUSED, ENDED
    }
}
