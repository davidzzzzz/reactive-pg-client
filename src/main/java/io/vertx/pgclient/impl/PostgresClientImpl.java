package io.vertx.pgclient.impl;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextImpl;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.net.impl.NetClientBase;
import io.vertx.core.net.impl.SSLHelper;
import io.vertx.core.spi.metrics.TCPMetrics;
import io.vertx.pgclient.PostgresClient;
import io.vertx.pgclient.PostgresClientOptions;
import io.vertx.pgclient.PostgresConnection;
import io.vertx.pgclient.PostgresConnectionPool;
import io.vertx.pgclient.codec.Message;
import io.vertx.pgclient.codec.decoder.MessageDecoder;
import io.vertx.pgclient.codec.encoder.MessageEncoder;
import io.vertx.pgclient.codec.encoder.message.StartupMessage;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class PostgresClientImpl extends NetClientBase<DbConnection> implements PostgresClient {

  final VertxInternal vertx;
  final String host;
  final int port;
  final String database;
  final String username;
  final String password;
  final int pipeliningLimit;

  public PostgresClientImpl(Vertx vertx, PostgresClientOptions options) {
    super((VertxInternal) vertx, options, true);
    this.host = options.getHost();
    this.port = options.getPort();
    this.database = options.getDatabase();
    this.username = options.getUsername();
    this.password = options.getPassword();
    this.pipeliningLimit = options.getPipeliningLimit();
    this.vertx = (VertxInternal) vertx;
  }

  public void connect(Handler<AsyncResult<PostgresConnection>> completionHandler) {
    doConnect(port, host, null, ar -> {
      if (ar.succeeded()) {
        DbConnection conn = ar.result();
        conn.handler = completionHandler;
        conn.writeToChannel(new StartupMessage(username, database));
      } else {
        completionHandler.handle(Future.failedFuture(ar.cause()));
      }
    });
  }

  @Override
  protected DbConnection createConnection(VertxInternal vertxInternal, Channel channel, String s, int i, ContextImpl context, SSLHelper sslHelper, TCPMetrics tcpMetrics) {
    return new DbConnection(this, vertxInternal, channel, context);
  }

  @Override
  public PostgresConnectionPool createPool(int size) {
    return new PostgresConnectionPoolImpl(this, size);
  }

  @Override
  protected void handleMsgReceived(DbConnection conn, Object o) {
    conn.handleMessage((Message) o);
  }

  @Override
  protected void initChannel(ChannelPipeline channelPipeline) {
    channelPipeline.addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 1, 4, -4, 0, true));
    channelPipeline.addLast(new MessageDecoder());
    channelPipeline.addLast(new MessageEncoder());
  }

  @Override
  protected Object safeObject(Object o, ByteBufAllocator byteBufAllocator) {
    return o;
  }
}
