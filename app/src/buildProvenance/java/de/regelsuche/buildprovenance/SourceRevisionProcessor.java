package de.regelsuche.buildprovenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

/** Shared Maven/Gradle build tool. Reads only a real source archive revision or the producing checkout. */
@SupportedAnnotationTypes("*")
@SupportedOptions("regelsucheSourceRoot")
public final class SourceRevisionProcessor extends AbstractProcessor {
    static final String RESOURCE = "META-INF/regelsuche/source-revision";
    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }

    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (!round.processingOver()) return false;
        try {
            String sha = resolve(Path.of(processingEnv.getOptions().get("regelsucheSourceRoot")));
            var output = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", RESOURCE);
            try (var writer = output.openWriter()) { writer.write(sha + "\n"); }
        } catch (IOException | IllegalArgumentException invalid) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, invalid.getMessage());
        }
        return false;
    }

    public static void main(String[] args) throws IOException {
        String revision = resolve(Path.of(args[0]));
        Path output = Path.of(args[1]).resolve(RESOURCE);
        Files.createDirectories(output.getParent());
        Files.writeString(output, revision + "\n");
    }

    static String resolve(Path root) throws IOException {
        String archive = Files.readString(root.resolve("app/src/buildProvenance/source-revision")).strip();
        String checkout = head(root.resolve(".git"));
        String declared = System.getenv("REGELSUCHE_PREPARATION_REVISION");
        String revision = archive.matches("[0-9a-f]{40}") ? archive : checkout;
        if (!archive.equals("$Format:%H$") && !archive.matches("[0-9a-f]{40}")) throw new IOException("Invalid source archive revision");
        if (declared != null && !declared.isBlank()) {
            if (!declared.matches("[0-9a-f]{40}") || !revision.isEmpty() && !declared.equals(revision)) {
                throw new IOException("Declared producing revision differs from source");
            }
            revision = declared;
        }
        if (!checkout.isEmpty() && !revision.equals(checkout)) throw new IOException("Archive revision differs from checkout");
        return revision;
    }

    private static String head(Path git) throws IOException {
        if (!Files.exists(git)) return "";
        if (Files.isRegularFile(git)) {
            String pointer = Files.readString(git).strip();
            if (!pointer.startsWith("gitdir: ")) throw new IOException("Invalid Git worktree pointer");
            git = git.getParent().resolve(pointer.substring(8)).normalize();
        }
        String head = Files.readString(git.resolve("HEAD")).strip();
        if (head.matches("[0-9a-f]{40}")) return head;
        if (!head.startsWith("ref: refs/") || head.contains("..")) throw new IOException("Invalid source HEAD");
        String ref = head.substring(5);
        Path common = Files.isRegularFile(git.resolve("commondir"))
            ? git.resolve(Files.readString(git.resolve("commondir")).strip()).normalize() : git;
        Path loose = common.resolve(ref);
        if (Files.isRegularFile(loose)) return checked(Files.readString(loose).strip());
        try (var lines = Files.lines(common.resolve("packed-refs"))) {
            return checked(lines.filter(line -> line.endsWith(" " + ref)).map(line -> line.split(" ", 2)[0])
                .findFirst().orElseThrow(() -> new IOException("Source HEAD reference missing")));
        }
    }

    private static String checked(String value) throws IOException {
        if (!value.matches("[0-9a-f]{40}")) throw new IOException("Invalid source revision");
        return value;
    }
}
