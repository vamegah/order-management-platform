package com.example.omp.shared.grpc;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reload validated identities in place: existing HTTP/2 transports are never restarted. */
public final class ReloadingTlsCredentials implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ReloadingTlsCredentials.class);
    private final Path certificate;
    private final Path privateKey;
    private final Path trustBundle;
    private final ScheduledExecutorService watcher;

    private record Contexts(SslContext server, SslContext client) {}

    private volatile Contexts contexts;
    private final SslContext server = new ReloadingSslContext(false, () -> contexts.server());
    private final SslContext client = new ReloadingSslContext(true, () -> contexts.client());

    @FunctionalInterface
    interface ProjectionReader {
        byte[] read(Path path) throws java.io.IOException;
    }

    private final ProjectionReader reader;
    private byte[] identityDigest;
    private byte[] trustDigest;

    public ReloadingTlsCredentials(
            Path certificate, Path privateKey, Path trustBundle, Duration interval)
            throws Exception {
        this(certificate, privateKey, trustBundle, interval, Files::readAllBytes);
    }

    ReloadingTlsCredentials(
            Path certificate,
            Path privateKey,
            Path trustBundle,
            Duration interval,
            ProjectionReader reader)
            throws Exception {
        this.reader = java.util.Objects.requireNonNull(reader);
        if (interval.compareTo(Duration.ofSeconds(1)) < 0
                || interval.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException(
                    "TLS reload interval must be between one second and one hour");
        this.certificate = certificate;
        this.privateKey = privateKey;
        this.trustBundle = trustBundle;
        // Invalid initial material fails startup; subsequent partial/invalid projections retain
        // last good state.
        reload();
        watcher =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread worker = new Thread(runnable, "grpc-tls-reload");
                            worker.setDaemon(true);
                            return worker;
                        });
        watcher.scheduleWithFixedDelay(
                this::refresh, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void refresh() {
        try {
            reload();
        } catch (Exception failure) {
            // Neither secrets nor provider/parser exception text belongs in logs.
            LOG.warn("Rejected gRPC TLS projection; retaining the last validated credentials");
        }
    }

    synchronized void reload() throws Exception {
        byte[] certBytes = reader.read(certificate);
        byte[] keyBytes = reader.read(privateKey);
        byte[] caBytes = reader.read(trustBundle);
        byte[] nextTrust = MessageDigest.getInstance("SHA-256").digest(caBytes);
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(certBytes);
        byte[] nextIdentity = digest.digest(keyBytes);
        try {
            if (Arrays.equals(identityDigest, nextIdentity)
                    && Arrays.equals(trustDigest, nextTrust)) return;
            X509Certificate[] chain = certificates(certBytes);
            X509Certificate[] roots = certificates(caBytes);
            PrivateKey key = privateKey(keyBytes, chain[0].getPublicKey().getAlgorithm());
            for (var cert : chain) cert.checkValidity();
            for (var root : roots) {
                root.checkValidity();
                if (root.getBasicConstraints() < 0)
                    throw new GeneralSecurityException("Trust anchor must be a CA");
            }
            var usage = chain[0].getExtendedKeyUsage();
            if (usage == null
                    || !usage.containsAll(
                            java.util.List.of("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2")))
                throw new GeneralSecurityException(
                        "Service identity requires serverAuth and clientAuth");
            String algorithm =
                    "EC".equals(key.getAlgorithm()) ? "SHA256withECDSA" : "SHA256withRSA";
            var signature = Signature.getInstance(algorithm);
            signature.initSign(key);
            signature.update(nextIdentity);
            byte[] proof = signature.sign();
            signature.initVerify(chain[0].getPublicKey());
            signature.update(nextIdentity);
            if (!signature.verify(proof))
                throw new GeneralSecurityException("Identity key mismatch");
            var store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            store.load(null, null);
            for (int i = 0; i < roots.length; i++) store.setCertificateEntry("ca-" + i, roots[i]);
            var factory =
                    javax.net.ssl.TrustManagerFactory.getInstance(
                            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            // Offline PKIX validation does not require an SSLEngine/Socket.
            ((javax.net.ssl.X509TrustManager) factory.getTrustManagers()[0])
                    .checkClientTrusted(chain, chain[0].getPublicKey().getAlgorithm());
            // Reject a projection changed while it was being read.
            if (!Arrays.equals(certBytes, reader.read(certificate))
                    || !Arrays.equals(keyBytes, reader.read(privateKey))
                    || !Arrays.equals(caBytes, reader.read(trustBundle)))
                throw new GeneralSecurityException("TLS projection changed during validation");
            // A context owns its session cache. A late handshake on the previous context
            // cannot repopulate a cache used by future connections after this atomic swap.
            var nextServer =
                    GrpcSslContexts.configure(
                                    SslContextBuilder.forServer(key, chain), SslProvider.JDK)
                            .trustManager(roots)
                            .clientAuth(ClientAuth.REQUIRE)
                            .build();
            var nextClient =
                    GrpcSslContexts.configure(SslContextBuilder.forClient(), SslProvider.JDK)
                            .keyManager(key, chain)
                            .trustManager(roots)
                            .build();
            contexts = new Contexts(nextServer, nextClient);
            identityDigest = nextIdentity;
            trustDigest = nextTrust;
            LOG.info("Loaded validated gRPC TLS identity and trust projection");
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private static X509Certificate[] certificates(byte[] pem) throws Exception {
        var certificates =
                CertificateFactory.getInstance("X.509")
                        .generateCertificates(new ByteArrayInputStream(pem));
        if (certificates.isEmpty())
            throw new GeneralSecurityException("Empty certificate collection");
        return certificates.toArray(X509Certificate[]::new);
    }

    private static PrivateKey privateKey(byte[] pem, String algorithm) throws Exception {
        String text = new String(pem, java.nio.charset.StandardCharsets.US_ASCII);
        if (!text.startsWith("-----BEGIN PRIVATE KEY-----"))
            throw new GeneralSecurityException("Use an unencrypted PKCS8 service key");
        byte[] encoded =
                Base64.getMimeDecoder()
                        .decode(
                                text.replace("-----BEGIN PRIVATE KEY-----", "")
                                        .replace("-----END PRIVATE KEY-----", ""));
        try {
            return KeyFactory.getInstance(algorithm)
                    .generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    public SslContext serverContext() {
        return server;
    }

    public SslContext clientContext() {
        return client;
    }

    @Override
    public void close() {
        watcher.shutdownNow();
    }
}
