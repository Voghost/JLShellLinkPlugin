package com.jlshell.link.plugin.program;

import com.google.gson.JsonObject;
import com.jlshell.link.client.ClientTlsIdentity;
import com.jlshell.link.client.ConnectionCoordinator;
import com.jlshell.link.client.LinkClientEngine;
import com.jlshell.link.client.RelayCarrierPlanFactory;
import com.jlshell.link.client.WebsiteAccessRequestProvider;
import com.jlshell.link.core.ProtocolVersion;
import com.jlshell.link.core.identity.Ed25519NodeKey;
import com.jlshell.link.core.identity.NodeKeyStore;
import com.jlshell.link.core.identity.NodeProofService;
import com.jlshell.link.core.identity.SecureSecretStore;
import com.jlshell.link.core.model.ConnectPolicy;
import com.jlshell.link.core.model.TargetEndpoint;
import com.jlshell.link.core.transport.TransportBudget;
import com.jlshell.link.transport.RelayProofClient;
import com.jlshell.link.transport.TlsHandshakeGate;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.plugin.api.storage.SecureStorage;
import com.jlshell.program.api.AccountSession;
import com.jlshell.program.api.AccountSessionService;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;

/** Owns A's in-process Java Link engine, bound to the current host account and secure device key. */
final class LinkClientRuntime implements AutoCloseable {
    private static final String NODE_KEY_PREFIX = "link.v2.client.node-key.";
    private static final String TLS_IDENTITY_PREFIX = "link.v2.client.tls-identity.";
    private static final Duration CONTROL_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CONTROL_REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RELAY_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RELAY_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final AccountSessionService sessions;
    private final SecureStorage secureStorage;
    private final PluginStorage pluginStorage;
    private final LinkV2AccountClient account;
    private final ExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<ConnectionCoordinator.PathAttemptEvent> lastPathAttempt = new AtomicReference<>();
    private final ConcurrentHashMap<UUID, LinkClientEngine.LocalTunnelLease> forwards = new ConcurrentHashMap<>();
    private volatile EngineContext active;
    private CompletableFuture<EngineContext> initializing;
    private long generation;

    LinkClientRuntime(AccountSessionService sessions, SecureStorage secureStorage,
            PluginStorage pluginStorage, LinkV2AccountClient account) {
        this.sessions = sessions == null ? AccountSessionService.unavailable() : sessions;
        this.secureStorage = secureStorage == null ? SecureStorage.unavailable() : secureStorage;
        this.pluginStorage = pluginStorage;
        this.account = Objects.requireNonNull(account, "account");
        this.worker = Executors.newFixedThreadPool(3, daemonFactory("jlshell-link-client-worker"));
    }

    CompletableFuture<LinkClientEngine.LocalTunnelLease> openTunnel(String agentId, String hostAddress, int port) {
        final UUID gateway;
        final TargetEndpoint target;
        try {
            gateway = UUID.fromString(Objects.requireNonNull(agentId, "agentId"));
            target = new TargetEndpoint(hostAddress, port);
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Link 连接需要有效的 Agent UUID 和数字 IP 目标", invalid));
        }
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Link 客户端已关闭"));

        CompletableFuture<LinkClientEngine.LocalTunnelLease> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<LinkClientEngine.LocalTunnelLease>> attempt = new AtomicReference<>();
        result.whenComplete((lease, error) -> {
            if (result.isCancelled()) {
                CompletableFuture<LinkClientEngine.LocalTunnelLease> pending = attempt.get();
                if (pending != null) pending.cancel(true);
            }
        });
        ensureEngine().whenComplete((runtime, prepareError) -> {
            if (result.isDone()) return;
            if (prepareError != null) {
                result.completeExceptionally(unwrap(prepareError));
                return;
            }
            if (closed.get()) {
                result.completeExceptionally(new IllegalStateException("Link 客户端已关闭"));
                return;
            }
            CompletableFuture<LinkClientEngine.LocalTunnelLease> connecting = runtime.engine.openTunnel(
                    new LinkClientEngine.TunnelRequest(runtime.scope, gateway, target, ConnectPolicy.RELAY_ONLY))
                    .toCompletableFuture();
            attempt.set(connecting);
            if (result.isCancelled()) connecting.cancel(true);
            connecting.whenComplete((lease, connectError) -> {
                if (connectError != null) result.completeExceptionally(unwrap(connectError));
                else if (!result.complete(lease)) lease.close();
            });
        });
        return result;
    }

    CompletableFuture<LocalForward> openForward(String agentId, String hostAddress, int port) {
        UUID gateway;
        TargetEndpoint target;
        try {
            gateway = UUID.fromString(Objects.requireNonNull(agentId, "agentId"));
            target = new TargetEndpoint(hostAddress, port);
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "本地转发需要有效的 Agent UUID 和数值 IP 目标", invalid));
        }
        CompletableFuture<LinkClientEngine.LocalTunnelLease> opening =
                openTunnel(agentId, target.address(), target.port());
        CompletableFuture<LocalForward> result = new CompletableFuture<>();
        result.whenComplete((forward, error) -> {
            if (result.isCancelled()) opening.cancel(true);
        });
        opening.whenComplete((lease, error) -> {
            if (error != null) {
                result.completeExceptionally(unwrap(error));
                return;
            }
            if (result.isCancelled()) {
                lease.close();
                return;
            }
            UUID forwardId = UUID.randomUUID();
            forwards.put(forwardId, lease);
            lease.closed().whenComplete((ignored, closeError) -> forwards.remove(forwardId, lease));
            LocalForward forward = new LocalForward(forwardId, gateway, target.address(), target.port(), lease.host(),
                    lease.port(), lease.path().name());
            if (!result.complete(forward)) {
                forwards.remove(forwardId, lease);
                lease.close();
            }
        });
        return result;
    }

    CompletableFuture<Boolean> closeForward(UUID forwardId) {
        LinkClientEngine.LocalTunnelLease lease = forwards.remove(Objects.requireNonNull(forwardId, "forwardId"));
        if (lease == null) return CompletableFuture.completedFuture(false);
        lease.close();
        return CompletableFuture.completedFuture(true);
    }

    record LocalForward(UUID id, UUID agentId, String targetIp, int targetPort,
            String localHost, int localPort, String path) { }

    JsonObject status() {
        AccountSession session = sessions.snapshot();
        JsonObject result = new JsonObject();
        result.addProperty("available", session.authenticated() && secureStorage.available());
        result.addProperty("state", !session.authenticated() ? "SIGNED_OUT"
                : !secureStorage.available() ? "SECURE_STORAGE_UNAVAILABLE"
                : active != null && active.engine.status().toCompletableFuture()
                        .getNow(new LinkClientEngine.RuntimeSnapshot(false, 0, 0)).running()
                        ? "RUNNING" : "READY_ON_CONNECT");
        result.addProperty("secureStorageAvailable", secureStorage.available());
        LinkClientEngine.RuntimeSnapshot snapshot = active == null ? null
                : active.engine.status().toCompletableFuture().getNow(null);
        result.addProperty("openTunnels", snapshot == null ? 0 : snapshot.openTunnels());
        result.addProperty("pendingTunnels", snapshot == null ? 0 : snapshot.pendingTunnels());
        result.addProperty("openForwards", forwards.size());
        ConnectionCoordinator.PathAttemptEvent diagnostic = lastPathAttempt.get();
        result.addProperty("lastPath", diagnostic == null ? null : diagnostic.path().name());
        result.addProperty("lastPathOutcome", diagnostic == null ? null : diagnostic.outcome().name());
        result.addProperty("lastFailureCategory", diagnostic == null
                ? null : diagnostic.failureCategory().map(Enum::name).orElse(null));
        result.addProperty("lastPathElapsedMillis", diagnostic == null ? 0 : diagnostic.elapsed().toMillis());
        return result;
    }

    /** Applies a saved non-secret endpoint change to the next connection. */
    void reset() {
        EngineContext previous;
        CompletableFuture<EngineContext> pending;
        synchronized (this) {
            generation++;
            previous = active;
            active = null;
            pending = initializing;
            initializing = null;
        }
        if (pending != null) pending.cancel(true);
        if (previous != null) previous.close();
        lastPathAttempt.set(null);
    }

    private CompletableFuture<EngineContext> ensureEngine() {
        synchronized (this) {
            if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Link 客户端已关闭"));
            if (active != null && active.isCurrent(sessions)) {
                return CompletableFuture.completedFuture(active);
            }
            if (active != null) {
                active.close();
                active = null;
            }
            if (initializing != null && !initializing.isDone()) return initializing;

            long expectedGeneration = generation;
            CompletableFuture<EngineContext> pending = new CompletableFuture<>();
            initializing = pending;
            CompletionStage<EngineContext> initialization;
            try { initialization = beginInitialization(); }
            catch (RuntimeException error) { initialization = CompletableFuture.failedFuture(error); }
            initialization.whenComplete((created, error) -> {
                synchronized (LinkClientRuntime.this) {
                    if (initializing == pending) initializing = null;
                    if (generation != expectedGeneration || pending.isCancelled()) {
                        if (created != null) created.close();
                        pending.completeExceptionally(new IllegalStateException("Link 客户端配置已变更"));
                        return;
                    }
                    if (error == null && !closed.get()) active = created;
                    else if (created != null) created.close();
                }
                if (error != null) pending.completeExceptionally(unwrap(error));
                else if (closed.get()) pending.completeExceptionally(new IllegalStateException("Link 客户端已关闭"));
                else if (!pending.isDone()) pending.complete(created);
            });
            return pending;
        }
    }

    private CompletionStage<EngineContext> beginInitialization() {
        AccountSession initial = sessions.snapshot();
        if (!initial.authenticated() || initial.accountId().isBlank() || initial.deviceId().isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("请先在 JLShell 中登录账号"));
        }
        if (!secureStorage.available()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "宿主加密存储不可用，无法安全保存 JLShell Link 客户端身份"));
        }
        URI websiteOrigin;
        try { websiteOrigin = URI.create(normalizeOrigin(initial.baseUrl())); }
        catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(new IllegalStateException("宿主 Website 地址无效", invalid));
        }
        return account.hostDeviceRecordId(initial.deviceId()).thenCompose(deviceRecordId ->
                CompletableFuture.supplyAsync(() -> createIdentity(initial), worker)
                        .thenCompose(identity -> account.bindClientIdentity(deviceRecordId, identity.nodeKey())
                                .thenApply(bound -> {
                                    if (!deviceRecordId.equals(bound.deviceId())
                                            || !identity.nodeKey().fingerprint().equals(bound.keyFingerprint())
                                            || !ProtocolVersion.V2.equals(bound.protocolVersion())) {
                                        throw new SecurityException("Website 绑定了不同的客户端设备身份");
                                    }
                                    return createEngine(initial, deviceRecordId, websiteOrigin, identity);
                                })));
    }

    private ClientTlsIdentity.LocalIdentity createIdentity(AccountSession session) {
        String scope = identityScope(session);
        HostSecureSecretStore secrets = new HostSecureSecretStore(secureStorage);
        HostNodeKeyStore nodeKeys = new HostNodeKeyStore(secrets, NODE_KEY_PREFIX + scope);
        try {
            return ClientTlsIdentity.loadOrCreate(secrets, TLS_IDENTITY_PREFIX + scope, nodeKeys);
        } catch (Exception error) {
            throw new java.util.concurrent.CompletionException(
                    new IllegalStateException("无法在 JLShell 宿主加密存储中初始化 Link 客户端身份", error));
        }
    }

    private EngineContext createEngine(AccountSession initial, UUID deviceRecordId, URI websiteOrigin,
            ClientTlsIdentity.LocalIdentity identity) {
        ensureSessionStillMatches(initial);
        URI relayUri = LinkClientSettings.validateRelayUri(LinkClientSettings.relayUri(pluginStorage));
        LinkClientEngine.Scope scope = scope(initial, identity);
        Supplier<LinkClientEngine.Scope> currentScope = () -> {
            AccountSession current = sessions.snapshot();
            return sessionMatches(initial, current) ? scope : null;
        };
        CachedControlCredential credential = new CachedControlCredential(account, deviceRecordId,
                identity.nodeKey().fingerprint().value());
        TransportBudget budget = new TransportBudget(65_536, 8_192, 32, 1_048_576,
                131_072, 4_194_304, 8, Duration.ofSeconds(20));
        EventLoopGroup eventLoops = new NioEventLoopGroup(2, daemonFactory("jlshell-link-netty"));
        RelayProofClient proof = new RelayProofClient(RELAY_CONNECT_TIMEOUT, RELAY_REQUEST_TIMEOUT,
                new NodeProofService(), worker);
        try {
            SSLContext outerTls = SSLContext.getDefault();
            RelayCarrierPlanFactory plans = new RelayCarrierPlanFactory(eventLoops, relayUri,
                    deviceRecordId, identity.nodeKey(), credential, outerTls,
                    identity.tlsIdentity()::forAgent, budget,
                    new TlsHandshakeGate(budget.maxConcurrentHandshakes()), proof);
            WebsiteAccessRequestProvider access = new WebsiteAccessRequestProvider(websiteOrigin,
                    CONTROL_CONNECT_TIMEOUT, CONTROL_REQUEST_TIMEOUT, credential, worker);
            LinkClientEngine engine = new LinkClientEngine(scope, currentScope, access, plans,
                    new ConnectionCoordinator.Config(Duration.ofSeconds(5), Duration.ofSeconds(15), 16),
                    lastPathAttempt::set, 16, Duration.ofSeconds(90));
            return new EngineContext(scope, initial, engine, proof, eventLoops);
        } catch (Exception error) {
            proof.close();
            eventLoops.shutdownGracefully(0, 5, java.util.concurrent.TimeUnit.SECONDS);
            throw new java.util.concurrent.CompletionException(error);
        }
    }

    private static LinkClientEngine.Scope scope(AccountSession session, ClientTlsIdentity.LocalIdentity identity) {
        return new LinkClientEngine.Scope(normalizeOrigin(session.baseUrl()), session.accountId(),
                identity.nodeKey().fingerprint().value(), ProtocolVersion.V2);
    }

    private static boolean sessionMatches(AccountSession left, AccountSession right) {
        return right != null && right.authenticated() && left.accountId().equals(right.accountId())
                && left.deviceId().equals(right.deviceId())
                && normalizeOrigin(left.baseUrl()).equals(normalizeOrigin(right.baseUrl()));
    }

    private static String normalizeOrigin(String value) {
        URI uri = URI.create(Objects.requireNonNull(value, "Website origin")).normalize();
        if (uri.getScheme() == null || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Website 地址必须是 HTTPS 纯 origin");
        }
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == 443;
        return scheme + "://" + host + (port < 0 || defaultPort ? "" : ":" + port);
    }

    private static String identityScope(AccountSession session) {
        try {
            byte[] input = (session.accountId() + "\n" + session.deviceId())
                    .getBytes(StandardCharsets.UTF_8);
            try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input)); }
            finally { Arrays.fill(input, (byte) 0); }
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void ensureSessionStillMatches(AccountSession initial) {
        if (!sessionMatches(initial, sessions.snapshot())) {
            throw new SecurityException("JLShell account session changed during Link identity setup");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        forwards.values().forEach(LinkClientEngine.LocalTunnelLease::close);
        forwards.clear();
        reset();
        worker.shutdownNow();
    }

    private static ThreadFactory daemonFactory(String prefix) {
        java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private final class CachedControlCredential implements Supplier<CompletionStage<String>> {
        private final LinkV2AccountClient api;
        private final UUID deviceRecordId;
        private final String fingerprint;
        private LinkV2AccountClient.ControlCredential cached;
        private CompletableFuture<LinkV2AccountClient.ControlCredential> pending;

        private CachedControlCredential(LinkV2AccountClient api, UUID deviceRecordId, String fingerprint) {
            this.api = api;
            this.deviceRecordId = deviceRecordId;
            this.fingerprint = fingerprint;
        }

        @Override
        public synchronized CompletionStage<String> get() {
            Instant refreshBefore = Instant.now().plusSeconds(15);
            if (cached != null && cached.expiresAt().isAfter(refreshBefore)) {
                return CompletableFuture.completedFuture(cached.value());
            }
            if (pending == null) {
                pending = api.issueControlCredential(deviceRecordId, fingerprint);
                CompletableFuture<LinkV2AccountClient.ControlCredential> request = pending;
                request.whenComplete((issued, error) -> {
                    synchronized (CachedControlCredential.this) {
                        if (pending == request) pending = null;
                        if (error == null) cached = issued;
                    }
                });
            }
            return pending.thenApply(LinkV2AccountClient.ControlCredential::value);
        }
    }

    private final class EngineContext implements AutoCloseable {
        private final LinkClientEngine.Scope scope;
        private final AccountSession session;
        private final LinkClientEngine engine;
        private final RelayProofClient proof;
        private final EventLoopGroup eventLoops;
        private final AtomicBoolean contextClosed = new AtomicBoolean();

        private EngineContext(LinkClientEngine.Scope scope, AccountSession session,
                LinkClientEngine engine, RelayProofClient proof, EventLoopGroup eventLoops) {
            this.scope = scope;
            this.session = session;
            this.engine = engine;
            this.proof = proof;
            this.eventLoops = eventLoops;
        }

        private boolean isCurrent(AccountSessionService currentSessions) {
            return sessionMatches(session, currentSessions.snapshot()) && engine.status().toCompletableFuture()
                    .getNow(new LinkClientEngine.RuntimeSnapshot(false, 0, 0)).running();
        }

        @Override public void close() {
            if (!contextClosed.compareAndSet(false, true)) return;
            engine.close();
            proof.close();
            eventLoops.shutdownGracefully(0, 5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static final class HostSecureSecretStore implements SecureSecretStore {
        private final SecureStorage storage;

        private HostSecureSecretStore(SecureStorage storage) { this.storage = storage; }

        @Override public Optional<byte[]> read(String key) throws IOException {
            try { return storage.get(key); }
            catch (RuntimeException error) { throw new IOException("Host secure storage read failed", error); }
        }

        @Override public void write(String key, byte[] secret) throws IOException {
            byte[] copy = Objects.requireNonNull(secret, "secret").clone();
            try { storage.put(key, copy); }
            catch (RuntimeException error) { throw new IOException("Host secure storage write failed", error); }
            finally { Arrays.fill(copy, (byte) 0); }
        }
    }

    /** Stores the client private key only through the host's encrypted SecureStorage API. */
    private static final class HostNodeKeyStore implements NodeKeyStore {
        private final SecureSecretStore secrets;
        private final String name;

        private HostNodeKeyStore(SecureSecretStore secrets, String name) {
            this.secrets = secrets;
            this.name = name;
        }

        @Override public Optional<Ed25519NodeKey> load() throws IOException, GeneralSecurityException {
            Optional<byte[]> stored = secrets.read(name);
            if (stored.isEmpty()) return Optional.empty();
            byte[] encoded = stored.orElseThrow();
            byte[] publicKey = null;
            byte[] privateKey = null;
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
                int publicLength = checkedLength(input.readInt());
                publicKey = input.readNBytes(publicLength);
                int privateLength = checkedLength(input.readInt());
                privateKey = input.readNBytes(privateLength);
                if (publicKey.length != publicLength || privateKey.length != privateLength || input.available() != 0) {
                    throw new IOException("Stored Link client key is malformed");
                }
                KeyFactory factory = KeyFactory.getInstance("Ed25519");
                return Optional.of(new Ed25519NodeKey(new KeyPair(
                        factory.generatePublic(new X509EncodedKeySpec(publicKey)),
                        factory.generatePrivate(new PKCS8EncodedKeySpec(privateKey)))));
            } finally {
                Arrays.fill(encoded, (byte) 0);
                if (publicKey != null) Arrays.fill(publicKey, (byte) 0);
                if (privateKey != null) Arrays.fill(privateKey, (byte) 0);
            }
        }

        @Override public void store(Ed25519NodeKey key) throws IOException {
            byte[] publicKey = key.publicKey().getEncoded();
            byte[] privateKey = key.privateKey().getEncoded();
            byte[] encoded = null;
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(publicKey.length);
                output.write(publicKey);
                output.writeInt(privateKey.length);
                output.write(privateKey);
                encoded = bytes.toByteArray();
                secrets.write(name, encoded);
            } finally {
                Arrays.fill(publicKey, (byte) 0);
                Arrays.fill(privateKey, (byte) 0);
                if (encoded != null) Arrays.fill(encoded, (byte) 0);
            }
        }

        private static int checkedLength(int length) throws IOException {
            if (length < 1 || length > 4096) throw new IOException("Stored Link client key length is invalid");
            return length;
        }
    }
}
