/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * <p>A <b>Valve</b> is a request processing component associated with a
 * particular Container.  A series of Valves are generally associated with
 * each other into a Pipeline.  The detailed contract for a Valve is included
 * in the description of the <code>invoke()</code> method below.</p>
 *
 * <b>HISTORICAL NOTE</b>:  The "Valve" name was assigned to this concept
 * because a valve is what you use in a real world pipeline to control and/or
 * modify flows through it.
 * <p>
 * 也就是说{@link Valve}是用来处理{@link Request}的组件, 多个{@link Valve}会组成一个{@link Pipeline}
 *
 * @author Craig R. McClanahan
 * @author Gunnar Rjnning
 * @author Peter Donald
 */
public interface Valve {


    //-------------------------------------------------------------- Properties

    /**
     * 获取pipeline中的下一个Valve
     */
     Valve getNext();


    /**
     * 设置下一个Valve
     *
     * @param valve The new next valve, or <code>null</code> if none
     */
     void setNext(Valve valve);


    //---------------------------------------------------------- Public Methods


    /**
     * 执行周期性任务，例如重新加载，等等此方法将在此容器的类加载上下文内被调用。
     * 意外抛出异常都会被捕获并记录
     */
     void backgroundProcess();


    /**
     * 执行Request请求, 一个Valve可以执行以下操作, 并按照指定的顺序：
     * 1.检查/修改指定的Request和Response的属性;
     * 2.检查指定Request的属性，完全生成相应的Response，并将控制权返回给调用者;
     * 3.检查指定Request和Response的属性, 包装这两个对象中的一个或两个以补充其功能，然后将其传递;
     * 4.如果相应的Response未生成, 即Controller不返回, 如果存在下一个Valve, 通过{@link #getNext()}获取pipeline的下一个Valve, 然后调用{@link #invoke(Request, Response)}执行
     * 5.检查但不能修改, 作为响应结果的Response, 它是由随后调用的Valve或Container创建的
     *
     * 一个Valve不能执行如下事情：
     * 1.更改已经用于指导此请求的处理控制流的请求属性（例如，在标准实现中，尝试更改应从连接到主机或上下文的管道向其发送请求的虚拟主机）
     * 2.创建一个完整的Response, 并将Request和Response传递到管道中的下一个Valve
     * 3.从与Request相关联的输入流中消耗字节，除非它完全生成了响应，或者在将请求传递之前包装了请求
     * 4.{@link #getNext()}/{@link #invoke(Request, Response)}执行后, 修改Response的HTTP返回头信息
     * 4.{@link #getNext()}/{@link #invoke(Request, Response)}执行后, 对与指定Response关联的输出流执行任何操作
     *
     * @param request  The servlet request to be processed
     * @param response The servlet response to be created
     * @throws IOException      if an input/output error occurs, or is thrown
     *                          by a subsequently invoked Valve, Filter, or Servlet
     * @throws ServletException if a servlet error occurs, or is thrown
     *                          by a subsequently invoked Valve, Filter, or Servlet
     */
     void invoke(Request request, Response response) throws IOException, ServletException;

    /**
     * 是否支持异步
     */
    boolean isAsyncSupported();
}
