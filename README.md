# sockjs-deadlock

**Description** 

Project to reproduce deadlock between two event loop threads when SockJSSession is closed from a callback at the same time the client closes a new XHR connection for that SockJSSession. We are using **vertx-web 3.2.1**.

The project consists of a main() that deploys and starts 8 instances of a ``Verticle`` called ``App``. This verticle starts an ``HttpServer`` that listens on port ``9876`` and uses *SockJS* to accept connections.

When a ``sockJSSocket`` is created, a timer is set, which will fire after *5 seconds* and will close the ``sockJSSocket``.
During this 5 seconds window, If a XHR request is made for that ``SockJSSession`` and the client closes this connection at the same moment the timer is closing the ``SockJSSession``, a deadlock may occur.

If only **1 verticle** is deployed, than we **don't have any deadlock** because the timer and the code that handles the client clossing a connection runs on the same event loop.

Due to the racing condition, the only way we found to reproduce it was using curl and an IDE with breakpoints. The steps are described below:

**Reproducing**

1. Run App.java as Debug from an IDE (we used IntelliJ).
2. Insert breakpoint at line 145 of ``SockJsSession.class``, inside method ``public synchronized void close()``.
3. Insert breakpoint at line 208 of ``BaseTransport.class``, inside method ``protected void addCloseHandler(HttpServerResponse resp, final SockJSSession session)``.
4. Insert breakpoint at line 219 of ``BaseTransport.class``, inside method ``public void sessionClosed()``.
5. Make a xhr call from the command line: ``curl -X POST http://localhost:9876/123/q1w2e3r4/xhr``. This will start the 5 seconds timer
6. After receiving "o" response, and before 5 seconds pass, call it again and leave the connection open: `curl -X POST http://localhost:9876/123/q1w2e3r4/xhr`.
7. The timer will trigger and call ``sockJSSocket.close();``. We will end up at *step 2* breakpoint.
8. Close the curl connection by hiting ctrl+c and wait a few seconds.
9. Resume breakpoint and we will stop at *step 4* breakpoint.
10. Resume breakpoint and we will stop at *step 3* breakpoint.
11. Resume breakpoint and we should get a deadlock.
12. If you could not reproduce it, please, try a couple more times starting from step 5 (with a new ``session_id`` in the uri).

**Thoughts**

What seems to be happening is:
* Thread 1 calls (after some timeout) ``SockJSSession.close()`` which is a **synchronized** method on ``SockJSSession``.
* At the same time Netty detects the client has also closed the connection on their side.
* Thread 2 calls ``HttpServerResponseImpl.handleClosed()``, which has a **synchronized statement on ServerConnection conn**.
* Later on, thread 2 calls ``SockJSSession.shutdown()`` (close handle callback for HttpServerResponse). ``SockJSSession.shutdown()`` is also a synchronized method on this ``sockJsSession`` object so it will not execute until thread 1 fully executes ``SockJSSession.close()``.
* Later on, thread 1, still on ``SockJSSession.close()`` execution path, tries to call ``HttpServerResponseImpl.end(HttpServerResponseImpl.java:328)``, which also has a synchronized statement on ServerConnection conn.

**Output**

Connected to the target VM, address: '127.0.0.1:60400', transport: 'socket'  
from main :vert.x-eventloop-thread-0  
Got Websocket Server  
from handler :vert.x-eventloop-thread-7 Verticle :rodolfocal.App@60a6639d  
from timeout : vert.x-eventloop-thread-7 Verticle :rodolfocal.App@60a6639d  
from context : vert.x-eventloop-thread-7 Verticle :rodolfocal.App@60a6639d  

**Stacktrace**:

After the output above (a similar one) we will have something like this:

**thread 1**

Mar 29, 2016 5:57:05 PM io.vertx.core.impl.BlockedThreadChecker
WARNING: Thread Thread[vert.x-eventloop-thread-7,5,main] has been blocked for 265537 ms, time limit is 2000
io.vertx.core.VertxException: Thread blocked
	at io.vertx.core.http.impl.HttpServerResponseImpl.end(HttpServerResponseImpl.java:328)
	at io.vertx.ext.web.handler.sockjs.impl.XhrTransport$XhrPollingListener.close(XhrTransport.java:178)
	at io.vertx.ext.web.handler.sockjs.impl.XhrTransport$XhrPollingListener.sendFrame(XhrTransport.java:170)
	at io.vertx.ext.web.handler.sockjs.impl.SockJSSession.writeClosed(SockJSSession.java:376)
	at io.vertx.ext.web.handler.sockjs.impl.SockJSSession.writeClosed(SockJSSession.java:369)
	at io.vertx.ext.web.handler.sockjs.impl.BaseTransport$BaseListener.sessionClosed(BaseTransport.java:132)
	at io.vertx.ext.web.handler.sockjs.impl.SockJSSession.close(SockJSSession.java:191)
	at rodolfocal.App.lambda$null$1(App.java:51)
	at rodolfocal.App$$Lambda$148/1118884252.handle(Unknown Source)
	at io.vertx.core.impl.ContextImpl.lambda$wrapTask$18(ContextImpl.java:335)
	at io.vertx.core.impl.ContextImpl$$Lambda$8/1392906938.run(Unknown Source)
	at io.netty.util.concurrent.SingleThreadEventExecutor.runAllTasks(SingleThreadEventExecutor.java:358)
	at io.netty.channel.nio.NioEventLoop.run(NioEventLoop.java:357)
	at io.netty.util.concurrent.SingleThreadEventExecutor$2.run(SingleThreadEventExecutor.java:112)
	at java.lang.Thread.run(Thread.java:745)
	
**thread 2**

This is the other thread that handles the client closing the second XHR connection.

Mar 29, 2016 5:57:05 PM io.vertx.core.impl.BlockedThreadChecker
WARNING: Thread Thread[vert.x-eventloop-thread-4,5,main] has been blocked for 206740 ms, time limit is 2000
io.vertx.core.VertxException: Thread blocked
	at io.vertx.ext.web.handler.sockjs.impl.SockJSSession.shutdown(SockJSSession.java:179)
	at io.vertx.ext.web.handler.sockjs.impl.BaseTransport$BaseListener$1.handle(BaseTransport.java:124)
	at io.vertx.core.VoidHandler.handle(VoidHandler.java:27)
	at io.vertx.core.VoidHandler.handle(VoidHandler.java:24)
	at io.vertx.core.http.impl.HttpServerResponseImpl.handleClosed(HttpServerResponseImpl.java:554)
	at io.vertx.core.http.impl.ServerConnection.handleClosed(ServerConnection.java:341)
	at io.vertx.core.net.impl.VertxHandler$$Lambda$147/1056622282.run(Unknown Source)
	at io.vertx.core.impl.ContextImpl.lambda$wrapTask$18(ContextImpl.java:333)
	at io.vertx.core.impl.ContextImpl$$Lambda$8/1392906938.run(Unknown Source)
	at io.vertx.core.impl.ContextImpl.executeFromIO(ContextImpl.java:225)
	at io.vertx.core.net.impl.VertxHandler.channelInactive(VertxHandler.java:99)
	at io.netty.channel.AbstractChannelHandlerContext.invokeChannelInactive(AbstractChannelHandlerContext.java:218)
	at io.netty.channel.AbstractChannelHandlerContext.fireChannelInactive(AbstractChannelHandlerContext.java:204)
	at io.netty.handler.codec.ByteToMessageDecoder.channelInactive(ByteToMessageDecoder.java:332)
	at io.netty.channel.AbstractChannelHandlerContext.invokeChannelInactive(AbstractChannelHandlerContext.java:218)
	at io.netty.channel.AbstractChannelHandlerContext.fireChannelInactive(AbstractChannelHandlerContext.java:204)
	at io.netty.channel.DefaultChannelPipeline.fireChannelInactive(DefaultChannelPipeline.java:828)
	at io.netty.channel.AbstractChannel$AbstractUnsafe$7.run(AbstractChannel.java:625)
	at io.netty.util.concurrent.SingleThreadEventExecutor.runAllTasks(SingleThreadEventExecutor.java:358)
	at io.netty.channel.nio.NioEventLoop.run(NioEventLoop.java:357)
	at io.netty.util.concurrent.SingleThreadEventExecutor$2.run(SingleThreadEventExecutor.java:112)
	at java.lang.Thread.run(Thread.java:745)
