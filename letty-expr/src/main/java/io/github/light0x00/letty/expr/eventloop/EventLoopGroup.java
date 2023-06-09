package io.github.light0x00.letty.expr.eventloop;

import io.github.light0x00.letty.expr.concurrent.ListenableFutureTask;

/**
 * @author light0x00
 * @since 2023/6/29
 */
public interface EventLoopGroup<T extends EventLoop> {

    T next();

    ListenableFutureTask<Void> shutdown();
}
