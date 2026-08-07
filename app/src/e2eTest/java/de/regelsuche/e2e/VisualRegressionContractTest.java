package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Verifies that the executable visual tests and their versioned policy cannot drift apart. */
class VisualRegressionContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void policyMatchesExecutableEnvironmentAndPinnedContainer() throws Exception {
        JsonNode policy = readPolicy();
        JsonNode environment = policy.path("environment");
        JsonNode comparison = policy.path("comparison");

        assertEquals("regelsuche.visual-regression-policy/v1", policy.path("schema").asText());
        assertEquals("chromium", environment.path("browser").asText());
        assertEquals(MathRenderingVisualTest.VIEWPORT_WIDTH,
            environment.path("viewportWidth").asInt());
        assertEquals(MathRenderingVisualTest.VIEWPORT_HEIGHT,
            environment.path("viewportHeight").asInt());
        assertEquals(MathRenderingVisualTest.DEVICE_SCALE_FACTOR,
            environment.path("deviceScaleFactor").asDouble());
        assertEquals(MathRenderingVisualTest.LOCALE, environment.path("locale").asText());
        assertEquals(MathRenderingVisualTest.TIMEZONE_ID,
            environment.path("timezoneId").asText());
        assertEquals(ScreenshotDiffUtil.CHANNEL_TOLERANCE,
            comparison.path("channelTolerance").asInt());
        assertEquals(ScreenshotDiffUtil.MAX_DIFF_RATIO,
            comparison.path("maxDiffRatio").asDouble());
        assertEquals(
            Integer.toHexString(ScreenshotDiffUtil.CHANGED_PIXEL_ARGB),
            comparison.path("changedPixelArgb").asText());

        String containerImage = environment.path("containerImage").asText();
        String playwrightVersion = environment.path("playwrightVersion").asText();
        assertFalse(containerImage.isBlank());
        assertFalse(playwrightVersion.isBlank());

        String dockerfile = Files.readString(requiredPath(List.of(
            Path.of("Dockerfile.visual-regression"),
            Path.of("..", "Dockerfile.visual-regression"))));
        assertTrue(dockerfile.lines().anyMatch(line -> line.equals("FROM " + containerImage)),
            "Dockerfile.visual-regression must use the policy-bound image " + containerImage);

        String appBuild = Files.readString(requiredPath(List.of(
            Path.of("app", "build.gradle"),
            Path.of("build.gradle"))));
        assertTrue(appBuild.contains("com.microsoft.playwright:playwright:" + playwrightVersion),
            "app/build.gradle must use the policy-bound Playwright version " + playwrightVersion);
    }

    @Test
    void policyBindsEveryCommittedBaselineByLengthAndGitBlobIdentity() throws Exception {
        JsonNode baselines = readPolicy().path("baselines");
        assertTrue(baselines.isObject(), "visual policy baselines must be an object");

        Path baselineDirectory = requiredPath(List.of(
            Path.of("app", "src", "e2eTest", "resources", "screenshots", "baseline"),
            Path.of("src", "e2eTest", "resources", "screenshots", "baseline")));
        Set<String> committedNames = new TreeSet<>();
        try (var files = Files.list(baselineDirectory)) {
            files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".png"))
                .map(path -> path.getFileName().toString())
                .forEach(committedNames::add);
        }

        Set<String> policyNames = new TreeSet<>();
        baselines.fieldNames().forEachRemaining(policyNames::add);
        assertEquals(committedNames, policyNames,
            "visual policy must bind exactly the committed PNG baselines");
        assertFalse(committedNames.isEmpty(), "at least one visual baseline is required");

        for (String name : committedNames) {
            Path baseline = baselineDirectory.resolve(name);
            JsonNode descriptor = baselines.path(name);
            byte[] bytes = Files.readAllBytes(baseline);
            assertEquals(bytes.length, descriptor.path("byteLength").asLong(),
                name + " byte length differs from the policy");
            assertEquals(gitBlobSha(bytes), descriptor.path("gitBlobSha").asText(),
                name + " Git blob identity differs from the policy");
            BufferedImage image = ImageIO.read(baseline.toFile());
            assertNotNull(image, name + " is not a readable PNG image");
            assertTrue(image.getWidth() > 0 && image.getHeight() > 0,
                name + " must have positive dimensions");
        }
    }

    private static JsonNode readPolicy() throws Exception {
        Path policy = requiredPath(List.of(
            Path.of("app", "src", "e2eTest", "resources", "screenshots",
                "visual-regression-policy.json"),
            Path.of("src", "e2eTest", "resources", "screenshots",
                "visual-regression-policy.json")));
        return JSON.readTree(policy.toFile());
    }

    private static String gitBlobSha(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static Path requiredPath(List<Path> candidates) {
        for (Path candidate : candidates) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.exists(absolute)) {
                return absolute;
            }
        }
        throw new IllegalStateException("Required path not found; tried " + candidates);
    }
}
