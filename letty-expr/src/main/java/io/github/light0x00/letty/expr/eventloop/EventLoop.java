package io.github.light0x00.letty.expr.eventloop;

import io.github.light0x00.letty.expr.concurrent.ListenableFutureTask;

import javax.annotation.Nonnull;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/**
 * @author light0x00
 * @since 2023/6/30
 */
public interface EventLoop extends Executor {

    default  <T> ListenableFutureTask<T> submit(@Nonnull Callable<T> callable) {
        return submit0(new ListenableFutureTask<>(callable, this));
    }

    default ListenableFutureTask<Void> submit(@Nonnull Runnable runnable) {
        return submit0(new ListenableFutureTask<>(runnable, null));
    }

    private <T> ListenableFutureTask<T> submit0(@Nonnull ListenableFutureTask<T> future) {
        execute(future);
        return future;
    }

    boolean inEventLoop();

    ListenableFutureTask<Void> shutdown();
}
