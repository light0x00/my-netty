package io.github.light0x00.letty.expr.eventloop;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;

/**
 * @author light0x00
 * @since 2023/6/29
 */
@Slf4j
public class SingleThreadExecutorGroup extends AbstractEventLoopGroup<SingleThreadExecutor> {

    public SingleThreadExecutorGroup(int threadNum) {
        super(threadNum);
    }

    public SingleThreadExecutorGroup(int threadNum, Executor executor) {
        super(threadNum, executor);
    }

    @Override
    protected SingleThreadExecutor newEventLoop(Executor executor) {
        return new SingleThreadExecutor(executor);
    }
}
