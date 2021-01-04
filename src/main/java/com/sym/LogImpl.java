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

    private static final Logger LOGGER = LoggerFactory.getLogger(LogImpl.class);

    public LogImpl() {
        this("");
    }

    public LogImpl(String name) {
        // 兼容tomcat日志
    }

    @Override
    public boolean isDebugEnabled() {
        return LOGGER.isDebugEnabled();
    }

    @Override
    public boolean isErrorEnabled() {
        return LOGGER.isErrorEnabled();
    }

    @Override
    public boolean isFatalEnabled() {
        return true;
    }

    @Override
    public boolean isInfoEnabled() {
        return LOGGER.isInfoEnabled();
    }

    @Override
    public boolean isTraceEnabled() {
        return LOGGER.isTraceEnabled();
    }

    @Override
    public boolean isWarnEnabled() {
        return LOGGER.isWarnEnabled();
    }

    @Override
    public void trace(Object message) {
        LOGGER.trace("{}", message);
    }

    @Override
    public void trace(Object message, Throwable t) {
        LOGGER.trace("{}", message, t);
    }

    @Override
    public void debug(Object message) {
        LOGGER.debug("{}", message);
    }

    @Override
    public void debug(Object message, Throwable t) {
        LOGGER.debug("{}", message, t);
    }

    @Override
    public void info(Object message) {
        LOGGER.info("{}", message);
    }

    @Override
    public void info(Object message, Throwable t) {
        LOGGER.info("{}", message, t);
    }

    @Override
    public void warn(Object message) {
        LOGGER.warn("{}", message);
    }

    @Override
    public void warn(Object message, Throwable t) {
        LOGGER.warn("{}", message, t);
    }

    @Override
    public void error(Object message) {
        LOGGER.error("{}", message);
    }

    @Override
    public void error(Object message, Throwable t) {
        LOGGER.error("{}", message, t);
    }

    @Override
    public void fatal(Object message) {
        LOGGER.error("{}", message);
    }

    @Override
    public void fatal(Object message, Throwable t) {
        LOGGER.error("{}", message, t);
    }
}
