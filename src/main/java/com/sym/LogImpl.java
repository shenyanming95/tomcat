package com.sym;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 自定义的{@link Log}实现类, 取代tomcat自带的JDK日志
 *
 * @author shenyanming
 * Created on 2021/1/4 11:23
 * @see LogFactory
 */
public class LogImpl implements Log {

    private Logger log;

    public LogImpl() {
        this("");
    }

    public LogImpl(String name) {
        // 兼容tomcat日志
        log = LoggerFactory.getLogger(name);
    }

    @Override
    public boolean isDebugEnabled() {
        return log.isDebugEnabled();
    }

    @Override
    public boolean isErrorEnabled() {
        return log.isErrorEnabled();
    }

    @Override
    public boolean isFatalEnabled() {
        return true;
    }

    @Override
    public boolean isInfoEnabled() {
        return log.isInfoEnabled();
    }

    @Override
    public boolean isTraceEnabled() {
        return log.isTraceEnabled();
    }

    @Override
    public boolean isWarnEnabled() {
        return log.isWarnEnabled();
    }

    @Override
    public void trace(Object message) {
        log.trace("{}", message);
    }

    @Override
    public void trace(Object message, Throwable t) {
        log.trace("{}", message, t);
    }

    @Override
    public void debug(Object message) {
        log.debug("{}", message);
    }

    @Override
    public void debug(Object message, Throwable t) {
        log.debug("{}", message, t);
    }

    @Override
    public void info(Object message) {
        log.info("{}", message);
    }

    @Override
    public void info(Object message, Throwable t) {
        log.info("{}", message, t);
    }

    @Override
    public void warn(Object message) {
        log.warn("{}", message);
    }

    @Override
    public void warn(Object message, Throwable t) {
        log.warn("{}", message, t);
    }

    @Override
    public void error(Object message) {
        log.error("{}", message);
    }

    @Override
    public void error(Object message, Throwable t) {
        log.error("{}", message, t);
    }

    @Override
    public void fatal(Object message) {
        log.error("{}", message);
    }

    @Override
    public void fatal(Object message, Throwable t) {
        log.error("{}", message, t);
    }
}
