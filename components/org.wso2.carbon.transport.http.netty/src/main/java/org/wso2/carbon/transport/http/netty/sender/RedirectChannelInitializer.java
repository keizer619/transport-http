/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.wso2.carbon.transport.http.netty.sender;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.transport.http.netty.common.Constants;
import org.wso2.carbon.transport.http.netty.listener.HTTPTraceLoggingHandler;

import javax.net.ssl.SSLEngine;

/**
 * Channel Initializer for for cross domain redirect handling
 */
public class RedirectChannelInitializer extends ChannelInitializer<SocketChannel> {

    private static final Logger log = LoggerFactory.getLogger(HTTPClientInitializer.class);

    private SSLEngine sslEngine; //Add SSL support to channel
    private boolean httpTraceLogEnabled; //Will be used, if enabled, to log events
    private int maxRedirectCount;
    private boolean chunkDisabled;
    private ChannelHandlerContext originalChannelContext;
    private boolean isIdleHandlerOfTargetChannelRemoved;

    public RedirectChannelInitializer(SSLEngine sslEngine, boolean httpTraceLogEnabled, int maxRedirectCount
            , boolean chunkDisabled, ChannelHandlerContext originalChannelContext
            , boolean isIdleHandlerOfTargetChannelRemoved) {
        this.sslEngine = sslEngine;
        this.httpTraceLogEnabled = httpTraceLogEnabled;
        this.maxRedirectCount = maxRedirectCount;
        this.chunkDisabled = chunkDisabled;
        this.originalChannelContext = originalChannelContext;
        this.isIdleHandlerOfTargetChannelRemoved = isIdleHandlerOfTargetChannelRemoved;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        // Add the generic handlers to the pipeline
        // e.g. SSL handler
        if (sslEngine != null) {
            if (log.isDebugEnabled()) {
                log.debug("adding ssl handler");
            }
            ch.pipeline().addLast("ssl", new SslHandler(this.sslEngine));
        }
        ch.pipeline().addLast("compressor", new HttpContentCompressor());
        ch.pipeline().addLast("decoder", new HttpResponseDecoder());
        ch.pipeline().addLast("encoder", new HttpRequestEncoder());
        if (httpTraceLogEnabled) {
            ch.pipeline().addLast(Constants.HTTP_TRACE_LOG_HANDLER,
                    new HTTPTraceLoggingHandler("tracelog.http.upstream", LogLevel.DEBUG));
        }
        RedirectHandler redirectHandler = new RedirectHandler(sslEngine, httpTraceLogEnabled, maxRedirectCount
                , chunkDisabled, originalChannelContext, isIdleHandlerOfTargetChannelRemoved);
        ch.pipeline().addLast(Constants.REDIRECT_HANDLER, redirectHandler);
    }

}