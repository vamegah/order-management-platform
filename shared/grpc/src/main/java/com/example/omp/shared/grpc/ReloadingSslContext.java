package com.example.omp.shared.grpc;

import io.grpc.netty.shaded.io.netty.buffer.ByteBufAllocator;
import io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolNegotiator;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslHandler;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSessionContext;

/** Keep the Netty registration stable while each new transport uses the latest complete context. */
final class ReloadingSslContext extends SslContext {
    private final boolean client;
    private final Supplier<SslContext> context;

    ReloadingSslContext(boolean client, Supplier<SslContext> context) {
        super(false);
        this.client = client;
        this.context = context;
    }

    @Override
    public boolean isClient() {
        return client;
    }

    @Override
    public List<String> cipherSuites() {
        return context.get().cipherSuites();
    }

    @Override
    public ApplicationProtocolNegotiator applicationProtocolNegotiator() {
        return context.get().applicationProtocolNegotiator();
    }

    @Override
    public SSLSessionContext sessionContext() {
        return context.get().sessionContext();
    }

    @Override
    public SSLEngine newEngine(ByteBufAllocator allocator) {
        return context.get().newEngine(allocator);
    }

    @Override
    public SSLEngine newEngine(ByteBufAllocator allocator, String host, int port) {
        return context.get().newEngine(allocator, host, port);
    }

    // Delegate handler construction as well: Netty's resumption controller belongs to its context.
    @Override
    protected SslHandler newHandler(ByteBufAllocator allocator, boolean startTls) {
        return context.get().newHandler(allocator);
    }

    @Override
    protected SslHandler newHandler(
            ByteBufAllocator allocator, boolean startTls, Executor executor) {
        return context.get().newHandler(allocator, executor);
    }

    @Override
    protected SslHandler newHandler(
            ByteBufAllocator allocator, String host, int port, boolean startTls) {
        return context.get().newHandler(allocator, host, port);
    }

    @Override
    protected SslHandler newHandler(
            ByteBufAllocator allocator,
            String host,
            int port,
            boolean startTls,
            Executor executor) {
        return context.get().newHandler(allocator, host, port, executor);
    }
}
