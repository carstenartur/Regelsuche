package de.regelsuche.web;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Optional security configuration for {@link WebWorkbenchServer}.
 *
 * <p>The web workbench started out as an explicitly local-only tool, so the
 * defaults remain "no auth, no TLS". This config object lets callers opt in
 * to HTTP Basic authentication and/or TLS without changing the existing
 * constructors.</p>
 *
 * <p>Notes:</p>
 * <ul>
 *   <li>Basic Auth is enabled by setting both {@link #username} and
 *       {@link #password}. The realm defaults to {@code "Regelsuche"}.</li>
 *   <li>TLS is enabled by providing a {@link #keystorePath} along with the
 *       keystore password. The keystore type defaults to {@code "PKCS12"}
 *       (the recommended modern default).</li>
 *   <li>Both options can be combined; for production deployments callers
 *       should set both.</li>
 * </ul>
 */
public final class WebSecurityConfig {
    private final String username;
    private final String password;
    private final String realm;
    private final Path keystorePath;
    private final char[] keystorePassword;
    private final String keystoreType;
    private final int maxRequestBytes;

    private WebSecurityConfig(Builder builder) {
        this.username = builder.username;
        this.password = builder.password;
        this.realm = builder.realm;
        this.keystorePath = builder.keystorePath;
        this.keystorePassword = builder.keystorePassword;
        this.keystoreType = builder.keystoreType;
        this.maxRequestBytes = builder.maxRequestBytes;
    }

    /** @return a configuration with no security enabled (legacy default). */
    public static WebSecurityConfig none() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isAuthEnabled() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    public boolean isTlsEnabled() {
        return keystorePath != null;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String realm() {
        return realm == null ? "Regelsuche" : realm;
    }

    public Path keystorePath() {
        return keystorePath;
    }

    public char[] keystorePassword() {
        return keystorePassword == null ? new char[0] : keystorePassword.clone();
    }

    public String keystoreType() {
        return keystoreType == null ? "PKCS12" : keystoreType;
    }

    public int maxRequestBytes() {
        return maxRequestBytes;
    }

    public static final class Builder {
        private String username;
        private String password;
        private String realm;
        private Path keystorePath;
        private char[] keystorePassword;
        private String keystoreType;
        private int maxRequestBytes = 1 << 20; // 1 MiB cap on JSON request bodies

        public Builder basicAuth(String username, String password) {
            this.username = Objects.requireNonNull(username, "username");
            this.password = Objects.requireNonNull(password, "password");
            return this;
        }

        public Builder realm(String realm) {
            this.realm = realm;
            return this;
        }

        public Builder tls(Path keystorePath, char[] keystorePassword) {
            return tls(keystorePath, keystorePassword, "PKCS12");
        }

        public Builder tls(Path keystorePath, char[] keystorePassword, String keystoreType) {
            this.keystorePath = Objects.requireNonNull(keystorePath, "keystorePath");
            this.keystorePassword = keystorePassword == null ? new char[0] : keystorePassword.clone();
            this.keystoreType = keystoreType;
            return this;
        }

        public Builder maxRequestBytes(int max) {
            if (max < 1024) {
                throw new IllegalArgumentException("maxRequestBytes must be >= 1024");
            }
            this.maxRequestBytes = max;
            return this;
        }

        public WebSecurityConfig build() {
            return new WebSecurityConfig(this);
        }
    }
}
