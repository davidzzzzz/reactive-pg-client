/*
 * Copyright (C) 2017 Julien Viet
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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.reactiverse.pgclient.impl;

import io.reactiverse.pgclient.PgConnection;
import io.reactiverse.pgclient.PgNotification;
import io.reactiverse.pgclient.PgTransaction;
import io.reactiverse.pgclient.copy.CopyFromOptions;
import io.reactiverse.pgclient.copy.CopyTuple;
import io.reactiverse.pgclient.copy.CopyWriteStream;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class PgConnectionImpl extends PgConnectionBase<PgConnectionImpl> implements PgConnection, Connection.Holder {

  private volatile Handler<Throwable> exceptionHandler;
  private volatile Handler<Void> closeHandler;
  private Transaction tx;
  private volatile Handler<PgNotification> notificationHandler;

  public PgConnectionImpl(Context context, Connection conn) {
    super(context, conn);
  }

  @Override
  public Connection connection() {
    return conn;
  }

  @Override
  public void handleClosed() {
    Handler<Void> handler = closeHandler;
    if (handler != null) {
      context.runOnContext(handler);
    }
  }

  @Override
  public <R> void schedule(CommandBase<R> cmd, Handler<? super CommandResponse<R>> handler) {
    cmd.handler = cr -> {
      // Tx might be gone ???
      cr.scheduler = this;
      handler.handle(cr);
    };
    schedule(cmd);
  }

  protected void schedule(CommandBase<?> cmd) {
    if (context == Vertx.currentContext()) {
      if (tx != null) {
        tx.schedule(cmd);
      } else {
        conn.schedule(cmd);
      }
    } else {
      context.runOnContext(v -> {
        schedule(cmd);
      });
    }
  }

  @Override
  public void handleException(Throwable err) {
    Handler<Throwable> handler = exceptionHandler;
    if (handler != null) {
      context.runOnContext(v -> {
        handler.handle(err);
      });
    } else {
      err.printStackTrace();
    }
  }

  @Override
  public boolean isSSL() {
    return conn.isSsl();
  }

  @Override
  public PgConnection closeHandler(Handler<Void> handler) {
    closeHandler = handler;
    return this;
  }

  @Override
  public PgConnection notificationHandler(Handler<PgNotification> handler) {
    notificationHandler = handler;
    return this;
  }

  @Override
  public PgConnection exceptionHandler(Handler<Throwable> handler) {
    exceptionHandler = handler;
    return this;
  }

  @Override
  public PgTransaction begin() {
    return begin(false);
  }

  PgTransaction begin(boolean closeOnEnd) {
    if (tx != null) {
      throw new IllegalStateException();
    }
    tx = new Transaction(context, conn, v -> {
      tx = null;
      if (closeOnEnd) {
        close();
      }
    });
    return tx;
  }

  public void handleNotification(int processId, String channel, String payload) {
    Handler<PgNotification> handler = notificationHandler;
    if (handler != null) {
      handler.handle(new PgNotification().setProcessId(processId).setChannel(channel).setPayload(payload));
    }
  }

  @Override
  public void close() {
    if (context == Vertx.currentContext()) {
      if (tx != null) {
        tx.rollback(ar -> conn.close(this));
        tx = null;
      } else {
        conn.close(this);
      }
    } else {
      context.runOnContext(v -> close());
    }
  }

  @Override
  public PgConnection copyFrom(String table, Handler<AsyncResult<CopyWriteStream<CopyTuple>>> stream) {
    return copyFrom(table, Collections.emptyList(), stream);
  }

  @Override
  public PgConnection copyFrom(String table, List<String> columns,
    Handler<AsyncResult<CopyWriteStream<CopyTuple>>> handler) {
    return beginCopy(table, columns, handler, CopyTupleWriteStreamImpl::new, CopyFromOptions.binary());
  }

  @Override
  public PgConnection copyFrom(String table, CopyFromOptions options, Handler<AsyncResult<CopyWriteStream<Buffer>>> handler) {
    return copyFrom(table, Collections.emptyList(), options, handler);
  }

  @Override
  public PgConnection copyFrom(String table, List<String> columns, CopyFromOptions options, Handler<AsyncResult<CopyWriteStream<Buffer>>> stream) {
    return beginCopy(table, columns, stream, CopyTextWriteStreamImpl::new, options);
  }

  private <T> PgConnection beginCopy(String table, List<String> columns,
    Handler<AsyncResult<CopyWriteStream<T>>> streamHandler,
    Function<Connection, CopyWriteStreamBase<T>> writeStreamSupplier, CopyFromOptions options) {

    schedule(new CopyInCommand(table, columns, options),
      res -> {
        if (res.failed()) {
          streamHandler.handle(Future.failedFuture(res.cause()));
        } else {
          CopyWriteStreamBase<T> stream = writeStreamSupplier.apply(conn);
          stream.writeHeader();
          streamHandler.handle(Future.succeededFuture(stream));
        }
      });
    return this;
  }
}
