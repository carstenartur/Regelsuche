import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/** Lossless-for-attribution compact export; original recordings remain the authority. */
public class ExtractJfr {
    public static void main(String[] args) throws Exception {
        try (var input = new RecordingFile(Path.of(args[0]));
             var output = new BufferedWriter(new OutputStreamWriter(
                 new GZIPOutputStream(Files.newOutputStream(Path.of(args[1]))), StandardCharsets.UTF_8))) {
            output.write("event\tepochNanos\tthread\tweight\tallocatedClass\ttruncated\tstack\n");
            long executions = 0, allocations = 0;
            while (input.hasMoreEvents()) {
                RecordedEvent event = input.readEvent();
                String name = event.getEventType().getName();
                if (!name.equals("jdk.ExecutionSample") && !name.equals("jdk.ObjectAllocationSample")) continue;
                boolean allocation = name.equals("jdk.ObjectAllocationSample");
                if (allocation) allocations++; else executions++;
                var instant = event.getStartTime();
                long epoch = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
                RecordedThread thread = event.getThread(allocation ? "eventThread" : "sampledThread");
                long weight = allocation ? event.getLong("weight") : 1;
                String type = allocation ? event.getClass("objectClass").getName() : "";
                var stack = event.getStackTrace();
                output.write(name + "\t" + epoch + "\t" + (thread == null ? "" : thread.getJavaName())
                    + "\t" + weight + "\t" + type + "\t" + (stack != null && stack.isTruncated()) + "\t");
                if (stack != null) {
                    boolean first = true;
                    for (RecordedFrame frame : stack.getFrames()) {
                        if (!first) output.write(";");
                        first = false;
                        var method = frame.getMethod();
                        output.write(method.getType().getName() + "." + method.getName() + ":" + frame.getLineNumber());
                    }
                }
                output.newLine();
            }
            System.out.println("executionSamples=" + executions + " allocationSamples=" + allocations);
        }
    }
}
