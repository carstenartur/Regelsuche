# Maven Student SDK archive

Move the Student SDK ZIP assembly into the Maven build contract. It consumes the existing isolated installer repository and exact product version. The archive retains canonical coordinate names, artifact checksums, documentation, tutorials, the independently pinned consumer adaptation, sorted members, 1980 DOS timestamps, and Unix modes. The manifest and extracted bytes must match the Python producer.

The existing Maven test module already has a release invocation boundary and secure POM parsing. A native Java command there can share those facilities. A new assembly descriptor would leave the source admission and provenance decisions in another producer and cannot express the pinned consumer derivation directly.

The release workflow rebuilds the same artifacts before invoking Java. For an existing historical tag without the Java implementation, the tag's existing Python producer remains the compatibility path. Optimization SDK packaging and all publication operations retain their existing behavior.

Hosted CI checks a fresh Maven stage on the exact source revision. It compares the Java ZIP to the old producer from the same stage and runs the existing Java 25 consumer verification against repository bytes extracted from the Java ZIP. The required check retains receipts and diagnostics, without retaining a Maven dependency cache or publishing either ZIP.
