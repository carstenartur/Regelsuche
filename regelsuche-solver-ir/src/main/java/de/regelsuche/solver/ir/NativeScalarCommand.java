package de.regelsuche.solver.ir;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.egraph.NativeScalarSaturation;
import de.regelsuche.egraph.proof.ScalarCertificateCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Exact source and assumptions are supplied by the receiving caller for both capture and reproducibility. */
public final class NativeScalarCommand {
    private NativeScalarCommand() { }
    public static void main(String[] args) {
        if (args.length>0 && args[0].equals("check")) {
            if (args.length<6) throw new IllegalArgumentException("scalar-saturation check <file> <digest> <revision> <source> <target> [nonzero variable ...]");
            try { NativeScalarEvidence.checkCertificate(Path.of(args[1]),args[2],args[4],args[5],premises(args,6),args[3]); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
            return;
        }
        if (args.length<5) throw new IllegalArgumentException("scalar-saturation run|verify <directory> <revision> <schedule> <expression> [expected-report-hash for verify] [nonzero variable ...]");
        var budget=new NativeScalarSaturation.Budget(12,1024,16000,NativeScalarSaturation.Schedule.valueOf(args[3]));
        boolean verify=args[0].equals("verify");
        if (!verify && !args[0].equals("run") || verify && args.length<6) throw new IllegalArgumentException("invalid scalar command");
        List<Assumption> assumptions=premises(args,verify?6:5);
        try {
            if (verify) NativeScalarEvidence.verify(Path.of(args[1]),args[5],args[4],assumptions,args[2],budget);
            else {
                var archive=NativeScalarEvidence.run(args[4],assumptions,args[2],budget);
                archive.write(Path.of(args[1]));
                System.out.println(ScalarCertificateCodec.hash(archive.report().getBytes(StandardCharsets.UTF_8)));
            }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private static List<Assumption> premises(String[] args,int from) {
        return Arrays.stream(args).skip(from).map(symbol -> {
            if (!symbol.matches("[a-zA-Z][a-zA-Z0-9_]*")) throw new IllegalArgumentException("expected a nonzero scalar variable");
            return Assumption.nonZero(symbol);
        }).toList();
    }
}
