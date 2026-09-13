package de.regelsuche.plugin;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

final class PluginDistributionFixtures implements AutoCloseable {
    final KeyPair rootKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    final KeyPair curatorKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    final KeyPair publisherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    final Map<String, byte[]> responses = new ConcurrentHashMap<>();
    final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final SSLContext tls;
    final HttpsServer server;
    final URI origin;
    final PluginTrustStore roots;
    final PluginTrustStore publishers;

    PluginDistributionFixtures() throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = getClass().getResourceAsStream("/plugin-distribution/loopback-test-only.p12")) {
            keys.load(input, "test-only-password".toCharArray());
        }
        var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, "test-only-password".toCharArray());
        var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keys);
        tls = SSLContext.getInstance("TLS");
        tls.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            byte[] bytes = responses.get(exchange.getRequestURI().getPath());
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        origin = URI.create("https://127.0.0.1:" + server.getAddress().getPort());
        roots = new PluginTrustStore(PluginTrustStore.SCHEMA,
            List.of(key("authority", rootKey, PluginTrustStore.KeyStatus.ACTIVE)), List.of());
        publishers = new PluginTrustStore(PluginTrustStore.SCHEMA,
            List.of(key("curator", curatorKey, PluginTrustStore.KeyStatus.ACTIVE),
                key("publisher", publisherKey, PluginTrustStore.KeyStatus.ACTIVE)), List.of());
    }

    static PluginTrustStore.PublisherKey key(String publisher, KeyPair pair,
            PluginTrustStore.KeyStatus status) {
        return new PluginTrustStore.PublisherKey(publisher, "key-1", "Ed25519",
            Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), status, "");
    }

    PluginArtifactIndex.Entry entry(String version, byte[] bytes) {
        String file = "example-" + version + ".jar";
        return PluginArtifactIndex.Entry.create("example-" + version,
            PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN, "example", version, "1", "0.5.0", "",
            List.of("algebra"), List.of(), file, PluginArtifactVerifier.sha256(bytes),
            origin.resolve("/" + file).toString(), origin.resolve("/" + file + ".sig.json").toString(),
            origin.resolve("/" + file + ".provenance.json").toString(), "publisher");
    }

    static String sign(KeyPair key, byte[] bytes) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(bytes);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    static String hash(String value) {
        return PluginArtifactVerifier.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}
