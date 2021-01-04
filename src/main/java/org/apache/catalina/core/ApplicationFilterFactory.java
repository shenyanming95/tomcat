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
package org.apache.catalina.core;

import org.apache.catalina.Globals;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Request;
import org.apache.tomcat.util.descriptor.web.FilterMap;

import javax.servlet.DispatcherType;
import javax.servlet.Servlet;
import javax.servlet.ServletRequest;

/**
 * Factory for the creation and caching of Filters and creation
 * of Filter Chains.
 *
 * @author Greg Murray
 * @author Remy Maucherat
 */
public final class ApplicationFilterFactory {

    private ApplicationFilterFactory() {
        // Prevent instance creation. This is a utility class.
    }


    /**
     * 构造一个FilterChain实现，该实现将包装指定servlet实例的执行。
     *
     * @param request 当前要处理的请求
     * @param wrapper 管理Servlet实例的Wrapper实例
     * @param servlet 实际Servlet(被Wrapper管理的)
     *
     * @return The configured FilterChain instance or null if none is to be executed.
     */
    public static ApplicationFilterChain createFilterChain(ServletRequest request, Wrapper wrapper, Servlet servlet) {
        // If there is no servlet to execute, return null
        if (servlet == null)
            return null;
        // 创建和初始化拦截器链
        ApplicationFilterChain filterChain = null;
        if (request instanceof Request) {
            Request req = (Request) request;
            if (Globals.IS_SECURITY_ENABLED) {
                // Security: Do not recycle
                filterChain = new ApplicationFilterChain();
            } else {
                filterChain = (ApplicationFilterChain) req.getFilterChain();
                if (filterChain == null) {
                    filterChain = new ApplicationFilterChain();
                    req.setFilterChain(filterChain);
                }
            }
        } else {
            // Request dispatcher in use
            filterChain = new ApplicationFilterChain();
        }
        // 拦截器设置Servlet实例和是否支持异步
        filterChain.setServlet(servlet);
        filterChain.setServletSupportsAsync(wrapper.isAsyncSupported());

        // 获取此StandardContext的过滤器映射, 就是配置在web.xml的filter标签
        StandardContext context = (StandardContext) wrapper.getParent();
        FilterMap[] filterMaps = context.findFilterMaps();

        // 如果没有设置拦截器, 那就直接返回喽
        if ((filterMaps == null) || (filterMaps.length == 0))
            return filterChain;

        // 获取需要匹配过滤器映射的信息
        DispatcherType dispatcher = (DispatcherType) request.getAttribute(Globals.DISPATCHER_TYPE_ATTR);

        String requestPath = null;
        Object attribute = request.getAttribute(Globals.DISPATCHER_REQUEST_PATH_ATTR);
        if (attribute != null){
            requestPath = attribute.toString();
        }
        String servletName = wrapper.getName();

        // 将相关的路径映射过滤器添加到此过滤器链
        for (FilterMap filterMap : filterMaps) {
            // 匹配过滤规则
            if (!matchDispatcher(filterMap, dispatcher)) {
                continue;
            }
            // 匹配请求路径
            if (!matchFiltersURL(filterMap, requestPath))
                continue;
            ApplicationFilterConfig filterConfig = (ApplicationFilterConfig)
                    context.findFilterConfig(filterMap.getFilterName());
            if (filterConfig == null) {
                continue;
            }
            filterChain.addFilter(filterConfig);
        }

        // 添加与servlet名称第二匹配的过滤器
        for (FilterMap filterMap : filterMaps) {
            if (!matchDispatcher(filterMap, dispatcher)) {
                continue;
            }
            // 匹配Servlet名
            if (!matchFiltersServlet(filterMap, servletName))
                continue;
            ApplicationFilterConfig filterConfig = (ApplicationFilterConfig)
                    context.findFilterConfig(filterMap.getFilterName());
            if (filterConfig == null) {
                continue;
            }
            filterChain.addFilter(filterConfig);
        }

        // Return the completed filter chain
        return filterChain;
    }


    // -------------------------------------------------------- Private Methods


    /**
     * Return <code>true</code> if the context-relative request path
     * matches the requirements of the specified filter mapping;
     * otherwise, return <code>false</code>.
     *
     * @param filterMap Filter mapping being checked
     * @param requestPath Context-relative request path of this request
     */
    private static boolean matchFiltersURL(FilterMap filterMap, String requestPath) {

        // Check the specific "*" special URL pattern, which also matches
        // named dispatches
        if (filterMap.getMatchAllUrlPatterns())
            return true;

        if (requestPath == null)
            return false;

        // Match on context relative request path
        String[] testPaths = filterMap.getURLPatterns();

        for (String testPath : testPaths) {
            if (matchFiltersURL(testPath, requestPath)) {
                return true;
            }
        }

        // No match
        return false;

    }


    /**
     * Return <code>true</code> if the context-relative request path
     * matches the requirements of the specified filter mapping;
     * otherwise, return <code>false</code>.
     *
     * @param testPath URL mapping being checked
     * @param requestPath Context-relative request path of this request
     */
    private static boolean matchFiltersURL(String testPath, String requestPath) {

        if (testPath == null)
            return false;

        // Case 1 - Exact Match
        if (testPath.equals(requestPath))
            return true;

        // Case 2 - Path Match ("/.../*")
        if (testPath.equals("/*"))
            return true;
        if (testPath.endsWith("/*")) {
            if (testPath.regionMatches(0, requestPath, 0,
                                       testPath.length() - 2)) {
                if (requestPath.length() == (testPath.length() - 2)) {
                    return true;
                } else if ('/' == requestPath.charAt(testPath.length() - 2)) {
                    return true;
                }
            }
            return false;
        }

        // Case 3 - Extension Match
        if (testPath.startsWith("*.")) {
            int slash = requestPath.lastIndexOf('/');
            int period = requestPath.lastIndexOf('.');
            if ((slash >= 0) && (period > slash)
                && (period != requestPath.length() - 1)
                && ((requestPath.length() - period)
                    == (testPath.length() - 1))) {
                return testPath.regionMatches(2, requestPath, period + 1,
                                               testPath.length() - 2);
            }
        }

        // Case 4 - "Default" Match
        return false; // NOTE - Not relevant for selecting filters

    }


    /**
     * Return <code>true</code> if the specified servlet name matches
     * the requirements of the specified filter mapping; otherwise
     * return <code>false</code>.
     *
     * @param filterMap Filter mapping being checked
     * @param servletName Servlet name being checked
     */
    private static boolean matchFiltersServlet(FilterMap filterMap,
                                        String servletName) {

        if (servletName == null) {
            return false;
        }
        // Check the specific "*" special servlet name
        else if (filterMap.getMatchAllServletNames()) {
            return true;
        } else {
            String[] servletNames = filterMap.getServletNames();
            for (String name : servletNames) {
                if (servletName.equals(name)) {
                    return true;
                }
            }
            return false;
        }

    }


    /**
     * Convenience method which returns true if  the dispatcher type
     * matches the dispatcher types specified in the FilterMap
     */
    private static boolean matchDispatcher(FilterMap filterMap, DispatcherType type) {
        switch (type) {
            case FORWARD :
                if ((filterMap.getDispatcherMapping() & FilterMap.FORWARD) != 0) {
                    return true;
                }
                break;
            case INCLUDE :
                if ((filterMap.getDispatcherMapping() & FilterMap.INCLUDE) != 0) {
                    return true;
                }
                break;
            case REQUEST :
                if ((filterMap.getDispatcherMapping() & FilterMap.REQUEST) != 0) {
                    return true;
                }
                break;
            case ERROR :
                if ((filterMap.getDispatcherMapping() & FilterMap.ERROR) != 0) {
                    return true;
                }
                break;
            case ASYNC :
                if ((filterMap.getDispatcherMapping() & FilterMap.ASYNC) != 0) {
                    return true;
                }
                break;
        }
        return false;
    }
}
