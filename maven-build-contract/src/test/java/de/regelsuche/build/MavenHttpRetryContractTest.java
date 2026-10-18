package de.regelsuche.build;

import static de.regelsuche.build.MavenPomTestSupport.repositoryRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MavenHttpRetryContractTest {
    @TempDir
    Path temporary;

    @Test
    void nativeHttpRetriesAreBoundedAndApplyBeforePomResolution() throws Exception {
        List<String> options = Files.readAllLines(repositoryRoot().resolve(".mvn/maven.config"))
            .stream().map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        assertEquals(List.of(
            "-Daether.connector.http.retryHandler.count=5",
            "-Daether.connector.http.retryHandler.interval=10000",
            "-Daether.connector.http.retryHandler.intervalMax=60000",
            "-Daether.transport.http.retryHandler.count=5",
            "-Daether.transport.http.retryHandler.interval=10000",
            "-Daether.transport.http.retryHandler.intervalMax=60000"), options);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void bomImportRecoversAfterMoreFailuresThanTheDefaultRetryCount(int status)
            throws Exception {
        assertBomResolution(status, 4, true, 5);
    }

    @Test
    void persistentRateLimitingStillFailsAfterTheConfiguredRetries() throws Exception {
        assertBomResolution(429, Integer.MAX_VALUE, false, 6);
    }

    @Test
    void missingArtifactsAreNotRetried() throws Exception {
        assertBomResolution(404, Integer.MAX_VALUE, false, 1);
    }

    private void assertBomResolution(
            int status, int failures, boolean success, int expectedRequests) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                if (!exchange.getRequestURI().getPath().equals(
                        "/fixture/retry-bom/1/retry-bom-1.pom")) {
                    exchange.sendResponseHeaders(404, -1);
                } else if (requests.incrementAndGet() <= failures) {
                    // Exercise real HTTP retries without sleeping in the test suite.
                    exchange.getResponseHeaders().add("Retry-After", "0");
                    exchange.sendResponseHeaders(status, -1);
                } else {
                    byte[] pom = """
                        <project xmlns="http://maven.apache.org/POM/4.0.0">
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>fixture</groupId><artifactId>retry-bom</artifactId>
                          <version>1</version><packaging>pom</packaging>
                        </project>
                        """.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, pom.length);
                    exchange.getResponseBody().write(pom);
                }
            }
        });
        server.start();
        try {
            Files.createDirectories(temporary.resolve(".mvn"));
            Files.copy(repositoryRoot().resolve(".mvn/maven.config"),
                temporary.resolve(".mvn/maven.config"));
            Files.writeString(temporary.resolve("settings.xml"), "<settings/>");
            Files.writeString(temporary.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>retry-consumer</artifactId>
                  <version>1</version><packaging>pom</packaging>
                  <repositories><repository>
                    <id>central</id><url>http://127.0.0.1:%d/</url>
                    <releases><checksumPolicy>ignore</checksumPolicy></releases>
                  </repository></repositories>
                  <dependencyManagement><dependencies><dependency>
                    <groupId>fixture</groupId><artifactId>retry-bom</artifactId>
                    <version>1</version><type>pom</type><scope>import</scope>
                  </dependency></dependencies></dependencyManagement>
                </project>
                """.formatted(server.getAddress().getPort()));
            Path log = temporary.resolve("maven.log");
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            List<String> command = new ArrayList<>();
            if (windows) {
                command.addAll(List.of("cmd.exe", "/c"));
            }
            command.addAll(List.of(windows ? "mvn.cmd" : "mvn",
                "validate", "-B", "-Denforcer.skip=true", "-Dcheckstyle.skip=true",
                "-Dlicense.skip=true", "-s", temporary.resolve("settings.xml").toString(),
                "-Dmaven.repo.local=" + temporary.resolve("repository")));
            Process process = new ProcessBuilder(command).directory(temporary.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(process.waitFor(90, TimeUnit.SECONDS),
                    () -> "Maven timed out: " + log);
                String output = Files.readString(log);
                if (success) {
                    assertEquals(0, process.exitValue(), output);
                } else {
                    assertNotEquals(0, process.exitValue(), output);
                    assertTrue(output.contains("retry-bom"), output);
                }
                assertEquals(expectedRequests, requests.get(), output);
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            }
        } finally {
            server.stop(0);
        }
    }
}
