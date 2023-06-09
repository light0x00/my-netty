package io.github.light0x00.letty.expr;

import io.github.light0x00.letty.expr.concurrent.FutureListener;
import io.github.light0x00.letty.expr.concurrent.ListenableFutureTask;
import io.github.light0x00.letty.expr.eventloop.NioEventLoop;
import io.github.light0x00.letty.expr.eventloop.NioEventLoopGroup;
import io.github.light0x00.letty.expr.handler.IOEventHandler;
import io.github.light0x00.letty.expr.handler.NioSocketChannel;
import lombok.SneakyThrows;

import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * 1. 创建 channel 并将其注册到 {@link NioEventLoop}
 *
 * @author light0x00
 * @since 2023/6/29
 */
public class Client {

    final NioEventLoopGroup group;

    ChannelConfigurationProvider channelConfigurationProvider;

    public Client(NioEventLoopGroup group, ChannelConfigurationProvider initializer) {
        this.group = group;
        this.channelConfigurationProvider = initializer;
    }

    @SneakyThrows
    public ListenableFutureTask<NioSocketChannel> connect(SocketAddress address) {
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(false);

        var connectedFuture = new ListenableFutureTask<NioSocketChannel>(null);

        NioEventLoop eventLoop = group.next();
        eventLoop.register(channel, SelectionKey.OP_CONNECT,
                        key -> {
                            IOEventHandler eventHandler = new IOEventHandler(eventLoop, channel, key, channelConfigurationProvider);
                            eventHandler.connectedFuture()
                                    .addListener((f) -> connectedFuture.setSuccess(eventHandler.channel()));
                            return eventHandler;
                        })
                .addListener(new FutureListener<SelectionKey>() {
                    @SneakyThrows
                    @Override
                    public void operationComplete(ListenableFutureTask<SelectionKey> f) {
                        // attach context before connect , so that to ensure the context not null when the event triggered.
                        // connect 动作应发生在 attach context 之后, 这样才能保证事件触发时能拿到非空的 context
                        channel.connect(address);
                    }
                });
        return connectedFuture;
    }

}
