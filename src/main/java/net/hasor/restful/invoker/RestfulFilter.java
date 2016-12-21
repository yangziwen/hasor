/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.restful.invoker;
import net.hasor.core.AppContext;
import net.hasor.restful.MimeType;
import net.hasor.restful.async.AsyncInvocationWorker;
import net.hasor.restful.async.AsyncSupported;
import net.hasor.web.ServletVersion;
import net.hasor.web.startup.RuntimeListener;
import org.more.util.ExceptionUtils;
import org.more.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
/**
 * action功能的入口。
 * @version : 2013-5-11
 * @author 赵永春 (zyc@hasor.net)
 */
class RestfulFilter implements Filter {
    protected     Logger            logger         = LoggerFactory.getLogger(getClass());
    private final AtomicBoolean     inited         = new AtomicBoolean(false);
    private       String[]          interceptNames = null;
    private       MappingToDefine[] invokeArray    = new MappingToDefine[0];
    private       MimeType          mimeType       = null;
    private       RenderLayout      renderLayout   = null;
    private       AppContext        appContext     = null;
    private       AsyncSupported    asyncSupported = AsyncSupported.yes;
    //
    public void init(FilterConfig filterConfig) throws ServletException {
        if (!this.inited.compareAndSet(false, true)) {
            return;
        }
        // 1.拦截
        this.appContext = RuntimeListener.getAppContext(filterConfig.getServletContext());
        String interceptNames = this.appContext.getEnvironment().getSettings().getString("hasor.restful.urlPatterns", "do;");
        Set<String> names = new HashSet<String>();
        for (String name : interceptNames.split(";")) {
            if (!StringUtils.isBlank(name)) {
                names.add(name);
            }
        }
        this.interceptNames = names.toArray(new String[names.size()]);
        //
        // 2.Find MappingInfoDefine
        List<MappingToDefine> mappingList = this.appContext.findBindingBean(MappingToDefine.class);
        Collections.sort(mappingList, new Comparator<MappingToDefine>() {
            public int compare(MappingToDefine o1, MappingToDefine o2) {
                return o1.getMappingTo().compareToIgnoreCase(o2.getMappingTo()) * -1;
            }
        });
        // 3.初始化
        for (MappingToDefine define : mappingList) {
            define.init(this.appContext);
        }
        this.invokeArray = mappingList.toArray(new MappingToDefine[mappingList.size()]);
        //
        //4.上下文
        try {
            this.mimeType = this.appContext.getInstance(MimeType.class);
            this.renderLayout = new RenderLayout();
            this.renderLayout.initEngine(this.appContext);
        } catch (Throwable e) {
            throw ExceptionUtils.toRuntimeException(e);
        }
    }
    //
    public void destroy() {
        //
    }
    //
    private MappingToDefine findMapping(String actionMethod, String actionPath) {
        for (MappingToDefine invoke : this.invokeArray) {
            if (invoke.matchingMapping(actionMethod, actionPath)) {
                return invoke;
            }
        }
        return null;
    }
    //
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = new HttpServletRequestWrapper((HttpServletRequest) req) {
            public RequestDispatcher getRequestDispatcher(final String path) {
                final RequestDispatcher dispatcher = getReqDispatcher(path, this);
                return null != dispatcher ? dispatcher : super.getRequestDispatcher(path);
            }
        };
        HttpServletResponse httpResponse = (HttpServletResponse) resp;
        String actionPath = httpRequest.getRequestURI().substring(httpRequest.getContextPath().length());
        String actionMethod = httpRequest.getMethod();
        InnerRenderData renderData = new InnerRenderData(this.appContext, this.mimeType, httpRequest, httpResponse);
        //
        // .Action 处理
        boolean doAction = false;
        for (String name : this.interceptNames) {
            if (actionPath.endsWith(name)) {
                MappingToDefine define = findMapping(actionMethod, actionPath);
                if (define != null) {
                    doInvoke(renderData, define, httpRequest, httpResponse);
                    doAction = true;
                    break;
                }
            }
        }
        //
        // .render
        try {
            if (this.renderLayout.process(renderData)) {
                return;
            } else {
                if (!doAction) {
                    chain.doFilter(req, resp);
                }
            }
        } catch (Throwable e) {
            logger.error("render '" + renderData.renderTo() + "' failed -> " + e.getMessage(), e);
            throw ExceptionUtils.toRuntimeException(e);
        }
    }
    //
    private void doInvoke(final InnerRenderData renderData, final MappingToDefine define, final HttpServletRequest httpRequest, final HttpServletResponse httpResponse) throws ServletException, IOException {
        // .要调用的目标是否要求异步执行?
        String actionMethod = httpRequest.getMethod();
        String actionPath = httpRequest.getRequestURI().substring(httpRequest.getContextPath().length());
        boolean needAsync = define.isAsync(actionMethod, actionPath) == AsyncSupported.yes;
        //
        // .开启异步Servlet ( 必须满足: Servlet3.x、环境支持异步Servlet、目标开启了Servlet3 )
        ServletVersion version = appContext.getInstance(ServletVersion.class);
        if (version.ge(ServletVersion.V3_0) && asyncSupported == AsyncSupported.yes && needAsync) {
            try {
                AsyncContext asyncContext = httpRequest.startAsync();
                asyncContext.start(new AsyncInvocationWorker(asyncContext, httpRequest, httpResponse) {
                    public void doWork(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                        _doWork(renderData, define, httpRequest);
                    }
                });
                return;
            } catch (Throwable e) {
                this.asyncSupported = AsyncSupported.no;//环境抛错,不支持asyc servlet.
            }
        }
        //
        _doWork(renderData, define, httpRequest);
    }
    private void _doWork(InnerRenderData renderData, MappingToDefine define, HttpServletRequest httpRequest) throws ServletException, IOException {
        try {
            define.invoke(renderData);
        } finally {
            httpRequest.removeAttribute(REQUEST_DISPATCHER_REQUEST);
        }
    }
    //
    //
    /** 为转发提供支持 */
    private RequestDispatcher getReqDispatcher(final String newRequestUri, final HttpServletRequest request) {
        // TODO 需要检查下面代码是否符合Servlet规范（带request参数情况下也需要检查）
        // 1.拆分请求字符串
        final MappingToDefine define = this.findMapping(request.getMethod(), newRequestUri);
        if (define == null) {
            return null;
        }
        //
        return new RequestDispatcher() {
            public void include(ServletRequest request, ServletResponse response) throws ServletException, IOException {
                request.setAttribute(REQUEST_DISPATCHER_REQUEST, Boolean.TRUE);// doInvoke 方法中会删除它。
                InnerRenderData renderData = new InnerRenderData(appContext, mimeType, (HttpServletRequest) request, (HttpServletResponse) response);
                doInvoke(renderData, define, (HttpServletRequest) request, (HttpServletResponse) response);
            }
            public void forward(ServletRequest request, ServletResponse response) throws ServletException, IOException {
                if (response.isCommitted())
                    throw new ServletException("Response has been committed--you can only call forward before committing the response (hint: don't flush buffers)");
                /* 清空缓冲 */
                response.resetBuffer();
                //
                /* 执行转发 */
                request.setAttribute(REQUEST_DISPATCHER_REQUEST, Boolean.TRUE);// doInvoke 方法中会删除它。
                HttpServletRequest requestToProcess = new RequestDispatcherRequestWrapper(request, newRequestUri);
                InnerRenderData renderData = new InnerRenderData(appContext, mimeType, (HttpServletRequest) request, (HttpServletResponse) response);
                doInvoke(renderData, define, requestToProcess, (HttpServletResponse) response);
            }
        };
    }
    //
    /** 使用RequestDispatcherRequestWrapper类处理request.getRequestURI方法的返回值 */
    public static final String REQUEST_DISPATCHER_REQUEST = "javax.servlet.forward.servlet_path";
    private static class RequestDispatcherRequestWrapper extends HttpServletRequestWrapper {
        private final String newRequestUri;
        public RequestDispatcherRequestWrapper(ServletRequest servletRequest, String newRequestUri) {
            super((HttpServletRequest) servletRequest);
            this.newRequestUri = newRequestUri;
        }
        public String getRequestURI() {
            return newRequestUri;
        }
    }
}