package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.ScreenshotType;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

final class ScreenshotDiffUtil {

    static final int CHANNEL_TOLERANCE = 12;
    static final double MAX_DIFF_RATIO = 0.002;
    static final int CHANGED_PIXEL_ARGB = 0xFFFF00FF;

    private static final String REPORT_DIRECTORY_ENVIRONMENT_VARIABLE =
        "REGELSUCHE_VISUAL_REPORT_DIR";
    private static final boolean UPDATE_BASELINES = Boolean.parseBoolean(
        System.getProperty("regelsuche.updateScreenshots", "false"));

    private ScreenshotDiffUtil() {
    }

    static void assertMatchesBaseline(Locator locator, String baselineName) throws IOException {
        Path baseline = baselineDir().resolve(baselineName + ".png");
        Path actual = reportDir().resolve(baselineName + ".actual.png");
        Path diff = reportDir().resolve(baselineName + ".diff.png");
        Files.createDirectories(actual.getParent());
        locator.screenshot(new Locator.ScreenshotOptions()
            .setPath(actual)
            .setType(ScreenshotType.PNG));
        if (UPDATE_BASELINES) {
            Files.createDirectories(baseline.getParent());
            Files.copy(actual, baseline, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        assertTrue(Files.isRegularFile(baseline),
            "Missing committed screenshot baseline " + baseline
                + "; refresh it only with regelsuche.updateScreenshots=true in the pinned container");
        BufferedImage expectedImage = ImageIO.read(baseline.toFile());
        BufferedImage actualImage = ImageIO.read(actual.toFile());
        assertTrue(expectedImage != null, "Unable to read baseline screenshot " + baseline);
        assertTrue(actualImage != null, "Unable to read actual screenshot " + actual);
        assertTrue(expectedImage.getWidth() == actualImage.getWidth()
                && expectedImage.getHeight() == actualImage.getHeight(),
            "Screenshot dimensions differ for " + baselineName + ": expected "
                + expectedImage.getWidth() + "x" + expectedImage.getHeight()
                + ", got " + actualImage.getWidth() + "x" + actualImage.getHeight());

        int width = expectedImage.getWidth();
        int height = expectedImage.getHeight();
        BufferedImage diffImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int changed = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int expectedRgb = expectedImage.getRGB(x, y);
                int actualRgb = actualImage.getRGB(x, y);
                if (!withinTolerance(expectedRgb, actualRgb)) {
                    changed++;
                    diffImage.setRGB(x, y, CHANGED_PIXEL_ARGB);
                } else {
                    diffImage.setRGB(x, y, actualRgb);
                }
            }
        }
        double ratio = changed / (double) (width * height);
        if (changed > 0) {
            ImageIO.write(diffImage, "png", diff.toFile());
        } else {
            Files.deleteIfExists(diff);
        }
        assertTrue(ratio <= MAX_DIFF_RATIO,
            "Screenshot diff for " + baselineName + " exceeded threshold: "
                + changed + " pixels (" + ratio + "), see " + diff);
    }

    private static boolean withinTolerance(int expectedRgb, int actualRgb) {
        return channelDelta(expectedRgb >> 16, actualRgb >> 16) <= CHANNEL_TOLERANCE
            && channelDelta(expectedRgb >> 8, actualRgb >> 8) <= CHANNEL_TOLERANCE
            && channelDelta(expectedRgb, actualRgb) <= CHANNEL_TOLERANCE
            && channelDelta(expectedRgb >> 24, actualRgb >> 24) <= CHANNEL_TOLERANCE;
    }

    private static int channelDelta(int left, int right) {
        return Math.abs((left & 0xFF) - (right & 0xFF));
    }

    private static Path baselineDir() {
        return locatePath(List.of(
            Path.of("src", "e2eTest", "resources", "screenshots", "baseline"),
            Path.of("app", "src", "e2eTest", "resources", "screenshots", "baseline")
        ));
    }

    private static Path reportDir() {
        String configuredDirectory = System.getenv(REPORT_DIRECTORY_ENVIRONMENT_VARIABLE);
        if (configuredDirectory != null && !configuredDirectory.isBlank()) {
            return Path.of(configuredDirectory).toAbsolutePath().normalize();
        }
        return locatePath(List.of(
            Path.of("build", "reports", "e2eTest", "screenshots"),
            Path.of("app", "build", "reports", "e2eTest", "screenshots")
        ));
    }

    private static Path locatePath(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (Files.exists(candidate.getParent() == null ? candidate : candidate.getParent())) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return candidates.get(0).toAbsolutePath().normalize();
    }
}
