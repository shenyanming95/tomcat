/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package javax.servlet;

/**
 * JavaWeb过滤器分为四类, 分别对应四种触发类型： REQUEST、FORWARD、INCLUDE、ERROR、ASYNC
 * 若在注册的时候没有特别声明，则默认为REQUEST。
 *
 * @since Servlet 3.0
 */
public enum DispatcherType {
    /**
     * 对forward动作指令进行过滤，并且无论是jsp页面内的Forward标签还是Java代码中的forward指令都会被过滤处理
     */
    FORWARD,

    /**
     * 对指定页面的include进行过滤，用法与Forward类型一致
     */
    INCLUDE,

    /**
     * 默认的, 对Request请求进行过滤
     */
    REQUEST,

    /**
     * 异步Servlet过滤
     */
    ASYNC,

    /**
     * 对错误访问的过滤，比较常见的错误为404、500等
     */
    ERROR
}
