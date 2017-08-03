/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.carbon.transport.http.netty.sender.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.connector.framework.websocket.WebSocketControlSignal;
import org.wso2.carbon.connector.framework.websocket.WebSocketObserver;
import org.wso2.carbon.transport.http.netty.common.Constants;
import org.wso2.carbon.transport.http.netty.exception.UnknownWebSocketFrameTypeException;
import org.wso2.carbon.transport.http.netty.internal.websocket.WebSocketChannelContextImpl;
import org.wso2.carbon.transport.http.netty.internal.websocket.WebSocketUtil;
import org.wso2.carbon.transport.http.netty.internal.websocket.WebSocketSessionImpl;
import org.wso2.carbon.transport.http.netty.internal.websocket.message.WebSocketBinaryMessageImpl;
import org.wso2.carbon.transport.http.netty.internal.websocket.message.WebSocketCloseMessageImpl;
import org.wso2.carbon.transport.http.netty.internal.websocket.message.WebSocketControlMessageImpl;
import org.wso2.carbon.transport.http.netty.internal.websocket.message.WebSocketTextMessageImpl;
import org.wso2.carbon.transport.http.netty.listener.WebSocketSourceHandler;

import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import javax.websocket.Session;

/**
 * WebSocket Client Handler. This class responsible for handling the inbound messages for the WebSocket Client.
 * <i>Note: If the user uses both WebSocket Client and the server it is recommended to check the
 * <b>{@link Constants}.IS_WEBSOCKET_SERVER</b> property to identify whether the message is coming from the client
 * or the server in the application level.</i>
 */
public class WebSocketTargetHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClient.class);

    private final WebSocketClientHandshaker handshaker;
    private final WebSocketSourceHandler sourceHandler;
    private final String requestedUri;
    private final WebSocketObserver observer;
    private final WebSocketChannelContextImpl webSocketChannelContext;
    private final ChannelHandlerContext ctx;
    private WebSocketSessionImpl clientSession;
    private ChannelPromise handshakeFuture;

    public WebSocketTargetHandler(WebSocketClientHandshaker handshaker, WebSocketSourceHandler sourceHandler,
                                  String requestedUri, ChannelHandlerContext ctx, WebSocketObserver observer,
                                  WebSocketChannelContextImpl webSocketChannelContext) {
        this.handshaker = handshaker;
        this.sourceHandler = sourceHandler;
        this.requestedUri = requestedUri;
        this.ctx = ctx;
        this.observer = observer;
        this.webSocketChannelContext = setupCommonProperties(webSocketChannelContext);
        handshakeFuture = null;
    }

    public ChannelFuture handshakeFuture() {
        return handshakeFuture;
    }

    public Session getClientSession() {
        return clientSession;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        handshakeFuture = ctx.newPromise();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws URISyntaxException {
        handshaker.handshake(ctx.channel());
        clientSession = WebSocketUtil.getSession(ctx, webSocketChannelContext.isConnectionSecured() , requestedUri);
        if (sourceHandler != null) {
            sourceHandler.addClientSession(clientSession);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("WebSocket Client disconnected!");
        int statusCode = 1001; // Client is going away.
        String reasonText = "Client is going away";
        WebSocketCloseMessageImpl webSocketCloseMessage =
                new WebSocketCloseMessageImpl(statusCode, reasonText, webSocketChannelContext);
        observer.update(webSocketCloseMessage);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg)
            throws UnknownWebSocketFrameTypeException, URISyntaxException {
        Channel ch = ctx.channel();
        if (!handshaker.isHandshakeComplete()) {
            handshaker.finishHandshake(ch, (FullHttpResponse) msg);
            log.debug("WebSocket Client connected!");
            handshakeFuture.setSuccess();
            clientSession = WebSocketUtil.getSession(ctx, webSocketChannelContext.isConnectionSecured() , requestedUri);
            if (sourceHandler != null) {
                sourceHandler.addClientSession(clientSession);
            }
            return;
        }

        if (msg instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) msg;
            throw new IllegalStateException(
                    "Unexpected FullHttpResponse (getStatus=" + response.status() +
                            ", content=" + response.content().toString(CharsetUtil.UTF_8) + ')');
        }
        WebSocketFrame frame = (WebSocketFrame) msg;
        if (frame instanceof TextWebSocketFrame) {
            TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
            String text = textFrame.text();
            boolean isFinalFragment = textFrame.isFinalFragment();
            WebSocketTextMessageImpl textMessage =
                    new WebSocketTextMessageImpl(text, isFinalFragment, webSocketChannelContext);
            observer.update(textMessage);

        } else if (frame instanceof BinaryWebSocketFrame) {
            BinaryWebSocketFrame binaryWebSocketFrame = (BinaryWebSocketFrame) msg;
            ByteBuf byteBuf = binaryWebSocketFrame.content();
            boolean finalFragment = binaryWebSocketFrame.isFinalFragment();
            ByteBuffer byteBuffer = byteBuf.nioBuffer();
            WebSocketBinaryMessageImpl binaryMessage =
                    new WebSocketBinaryMessageImpl(byteBuffer, finalFragment, webSocketChannelContext);
            observer.update(binaryMessage);

        } else if (frame instanceof PongWebSocketFrame) {
            PongWebSocketFrame pongWebSocketFrame = (PongWebSocketFrame) msg;
            ByteBuf byteBuf = pongWebSocketFrame.content();
            ByteBuffer byteBuffer = byteBuf.nioBuffer();
            WebSocketControlMessageImpl webSocketControlMessage =
                    new WebSocketControlMessageImpl(WebSocketControlSignal.PONG, byteBuffer, webSocketChannelContext);
            observer.update(webSocketControlMessage);

        } else if (frame instanceof PingWebSocketFrame) {
            PingWebSocketFrame pingFrame = (PingWebSocketFrame) frame;
            ctx.channel().writeAndFlush(new PongWebSocketFrame(pingFrame.content()));
        } else if (frame instanceof CloseWebSocketFrame) {
            CloseWebSocketFrame closeWebSocketFrame = (CloseWebSocketFrame) msg;
            String reasonText = closeWebSocketFrame.reasonText();
            int statusCode = closeWebSocketFrame.statusCode();
            ctx.channel().close();
            WebSocketCloseMessageImpl webSocketCloseMessage =
                    new WebSocketCloseMessageImpl(statusCode, reasonText, webSocketChannelContext);
            observer.update(webSocketCloseMessage);

        } else {
            throw new UnknownWebSocketFrameTypeException("Cannot identify the WebSocket frame type");
        }
    }

    private WebSocketChannelContextImpl setupCommonProperties(WebSocketChannelContextImpl webSocketChannelContext) {
        webSocketChannelContext.setProperty(Constants.SRC_HANDLER, this);
        webSocketChannelContext.setProperty(org.wso2.carbon.messaging.Constants.LISTENER_PORT,
                                            ((InetSocketAddress) ctx.channel().localAddress()).getPort());
        webSocketChannelContext.setProperty(Constants.LOCAL_ADDRESS, ctx.channel().localAddress());
        webSocketChannelContext.setProperty(
                Constants.LOCAL_NAME, ((InetSocketAddress) ctx.channel().localAddress()).getHostName());
        return webSocketChannelContext;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!handshakeFuture.isDone()) {
            log.error("Handshake failed : " + cause.getMessage(), cause);
            handshakeFuture.setFailure(cause);
        }
        observer.handleError(cause);
        ctx.close();
    }
}
