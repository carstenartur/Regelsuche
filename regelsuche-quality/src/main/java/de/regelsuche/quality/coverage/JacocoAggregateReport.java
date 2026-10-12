package de.regelsuche.quality.coverage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.FileMultiReportOutput;
import org.jacoco.report.IReportGroupVisitor;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.MultiReportVisitor;
import org.jacoco.report.html.HTMLFormatter;
import org.jacoco.report.xml.XMLFormatter;

/** Assembles measured modules; JaCoCo owns all analysis, HTML, XML and navigation. */
public final class JacocoAggregateReport {
    private JacocoAggregateReport() { }

    public static void main(String[] args) throws IOException {
        if (args.length < 3 || args.length % 2 != 1) {
            throw new IllegalArgumentException("Usage: output-directory module input-directory [module input-directory ...]");
        }
        Map<String, Path> inputs = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            String name = args[index];
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")
                    || inputs.putIfAbsent(name, Path.of(args[index + 1])) != null) {
                throw new IllegalArgumentException("Invalid or duplicate module: " + name);
            }
        }
        write(Path.of(args[0]), inputs);
    }

    static void write(Path output, Map<String, Path> inputs) throws IOException {
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("At least one measured module is required");
        }
        List<MeasuredModule> modules = new ArrayList<>();
        List<SessionInfo> sessions = new ArrayList<>();
        List<ExecutionData> execution = new ArrayList<>();
        for (Map.Entry<String, Path> entry : inputs.entrySet()) {
            MeasuredModule module = load(entry.getKey(), entry.getValue());
            modules.add(module);
            sessions.addAll(module.data().getSessionInfoStore().getInfos());
            execution.addAll(module.data().getExecutionDataStore().getContents());
        }
        // Do not reuse stale pages from an earlier module/class inventory.
        if (Files.exists(output)) {
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(output);
        try (OutputStream xml = Files.newOutputStream(output.resolve("jacoco.xml"))) {
            IReportVisitor visitor = new MultiReportVisitor(List.of(
                new HTMLFormatter().createVisitor(new FileMultiReportOutput(output.toFile())),
                new XMLFormatter().createVisitor(xml)));
            visitor.visitInfo(sessions, execution);
            IReportGroupVisitor aggregate = visitor.visitGroup("Regelsuche Coverage");
            for (MeasuredModule module : modules) {
                aggregate.visitBundle(module.coverage(),
                    new DirectorySourceFileLocator(module.sources().toFile(), "UTF-8", 4));
            }
            visitor.visitEnd();
        }
    }

    private static MeasuredModule load(String name, Path inputs) throws IOException {
        Path classes = inputs.resolve("classes");
        Path sources = inputs.resolve("sources");
        if (!Files.isDirectory(classes) || !Files.isDirectory(sources)) {
            throw new IOException("Missing retained classes or sources for " + name);
        }
        ExecFileLoader data = new ExecFileLoader();
        List<Path> files;
        try (var paths = Files.list(inputs.resolve("execution-data"))) {
            files = paths.filter(path -> Files.isRegularFile(path)
                && path.getFileName().toString().endsWith(".exec")).sorted().toList();
        }
        if (files.isEmpty()) {
            throw new IOException("Missing retained execution data for " + name);
        }
        for (Path path : files) {
            data.load(path.toFile());
        }
        // Separate stores are essential: app tests must not silently change
        // core's canonical measurement, nor may equal class IDs cross modules.
        CoverageBuilder coverage = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(data.getExecutionDataStore(), coverage);
        if (analyzer.analyzeAll(classes.toFile()) == 0 || !coverage.getNoMatchClasses().isEmpty()) {
            throw new IOException("Missing or mismatched measured classes for " + name);
        }
        return new MeasuredModule(coverage.getBundle(name), sources, data);
    }

    private record MeasuredModule(IBundleCoverage coverage, Path sources, ExecFileLoader data) { }
}
