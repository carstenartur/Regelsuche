package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScreenshotComparisonEvidenceTest {
    @TempDir Path directory;

    @Test
    void retainsExactBoundaryAndFailedPixelMeasurementsBeforeAssertion() throws Exception {
        Path baseline = directory.resolve("baseline.png");
        Path actual = directory.resolve("fixture.actual.png");
        Path diff = directory.resolve("fixture.diff.png");
        BufferedImage image = new BufferedImage(100, 10, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", baseline.toFile());
        for (int count : new int[] {0, 2, 3}) {
            for (int x = 0; x < count; x++) image.setRGB(x, 0, 0xffffffff);
            ImageIO.write(image, "png", actual.toFile());
            if (count <= 2) {
                ScreenshotDiffUtil.assertImagesMatchBaseline(baseline, actual, diff, "fixture");
            } else {
                assertThrows(AssertionError.class, () ->
                    ScreenshotDiffUtil.assertImagesMatchBaseline(baseline, actual, diff, "fixture"));
            }
            var receipt = new ObjectMapper().readTree(
                directory.resolve("fixture.comparison.json").toFile());
            assertEquals(count, receipt.path("changedPixels").asInt());
            assertEquals(1000, receipt.path("totalPixels").asInt());
            assertEquals(count / 1000.0, receipt.path("diffRatio").asDouble());
            assertEquals(count <= 2 ? "PASSED" : "FAILED", receipt.path("status").asText());
            assertEquals(12, receipt.path("channelTolerance").asInt());
            assertEquals(0.002, receipt.path("maxDiffRatio").asDouble());
            assertTrue(receipt.path("baselineSha256").asText().matches("sha256:[0-9a-f]{64}"));
            assertTrue(receipt.path("actualSha256").asText().matches("sha256:[0-9a-f]{64}"));
            assertEquals(count > 0, Files.isRegularFile(diff));
        }
    }
}
