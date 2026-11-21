package de.regelsuche.egraph.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.*;
import de.regelsuche.scalar.ExactRational;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static de.regelsuche.egraph.proof.ScalarExtractionProof.*;

/** Bounds the complete mixed certificate, including context, detached ground lemmas and the original ordered proof DAG. */
public final class ScalarCertificateCodec {
    public static final String SCHEMA = "regelsuche.native-scalar-extraction/v1";
    public static final int MAX_BYTES = 2_097_152;
    private ScalarCertificateCodec() {}
    public static byte[] encode(Bundle bundle, ProofLimits limits) {
        try {
            var buffer = new ByteArrayOutputStream();
            var out = new DataOutputStream(buffer);
            out.writeUTF(SCHEMA); writeContext(out,bundle.context());
            out.writeInt(bundle.lemmas().size());
            for (var lemma : bundle.lemmas()) {
                out.writeUTF(lemma.ruleId()); expr(out,lemma.before(),0); expr(out,lemma.after(),0);
                var backend = lemma.backend();
                out.writeBoolean(backend != null);
                if (backend != null) {
                    out.writeUTF(backend.backend()); out.writeUTF(backend.obligationJson());
                    out.writeUTF(backend.executionJson()); out.writeUTF(backend.resultJson());
                }
                if (buffer.size()>MAX_BYTES) throw new IOException("scalar certificate exceeds limit");
            }
            var dag=ExtractionCertificateCodec.encode(bundle.dag(),limits);
            if (buffer.size()+4L+dag.length>MAX_BYTES) throw new IOException("scalar certificate exceeds limit");
            out.writeInt(dag.length); out.write(dag);
            return buffer.toByteArray();
        } catch (IOException invalid) { throw new IllegalArgumentException("invalid scalar certificate",invalid); }
    }
    private static void writeContext(DataOutputStream out,Context context) throws IOException {
        out.writeUTF(context.domain()); out.writeUTF(context.analysis()); out.writeUTF(context.sourceRevision());
        out.writeInt(context.inventory().size());
        for (var rule : context.inventory()) { out.writeUTF(rule.id()); out.writeUTF(rule.contentHash()); out.writeUTF(rule.disposition()); }
        out.writeInt(context.assumptions().size());
        for (var a : context.assumptions()) {
            out.writeUTF(a.kind().name()); out.writeUTF(a.expression()); out.writeInt(a.symbols().size());
            for (var s:a.symbols()) out.writeUTF(s);
        }
    }
    public static String contextHash(Context context) {
        try { var bytes = new ByteArrayOutputStream(); writeContext(new DataOutputStream(bytes),context); return hash(bytes.toByteArray()); }
        catch (IOException invalid) { throw new IllegalArgumentException("invalid scalar context",invalid); }
    }
    public static String hash(byte[] bytes) {
        try { return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** The receiver owns the expected object digest and the context, separately from the untrusted bytes. */
    public static Bundle read(byte[] bytes, String expectedHash, Context expected, ProofLimits limits) {
        if (bytes.length>MAX_BYTES || !hash(bytes).equals(expectedHash)) throw new IllegalArgumentException("scalar certificate digest or size mismatch");
        try (var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!in.readUTF().equals(SCHEMA)) throw new IOException("scalar schema mismatch");
            var expectedBytes=new ByteArrayOutputStream(); writeContext(new DataOutputStream(expectedBytes),expected);
            if (!Arrays.equals(in.readNBytes(expectedBytes.size()),expectedBytes.toByteArray())) throw new IOException("source context mismatch");
            int count=count(in,Math.min(limits.maxProofSteps(),4096)); var lemmas=new ArrayList<Lemma>(count);
            for (int i=0;i<count;i++) {
                var rule=in.readUTF(); var before=expr(in,0); var after=expr(in,0);
                var witness=in.readBoolean()?new BackendWitness(in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF()):null;
                lemmas.add(new Lemma(rule,before,after,witness));
            }
            int size=count(in,MAX_BYTES);
            var dag=in.readNBytes(size);
            if (dag.length!=size || in.read()!=-1) throw new IOException("invalid scalar proof frame");
            var bundle=new Bundle(expected,lemmas,ExtractionCertificateCodec.decode(dag,limits));
            if (!Arrays.equals(bytes,encode(bundle,limits))) throw new IOException("noncanonical scalar certificate");
            return bundle;
        } catch (IOException malformed) { throw new IllegalArgumentException("invalid scalar certificate",malformed); }
    }
    private static int count(DataInputStream in,int max) throws IOException {
        int n=in.readInt(); if (n<0 || n>max) throw new IOException("scalar frame limit"); return n;
    }
    private static void expr(DataOutputStream out,Expr e,int depth) throws IOException {
        if (depth>256) throw new IOException("scalar term depth limit");
        switch(e) {
            case NumberExpr n -> { out.writeByte(0); out.writeUTF(n.value().canonicalText()); }
            case VariableExpr v -> { out.writeByte(1); out.writeUTF(v.name()); }
            case BinaryExpr b -> { out.writeByte(2); out.writeUTF(b.operator().name()); expr(out,b.left(),depth+1); expr(out,b.right(),depth+1); }
            case FunctionExpr f -> {
                out.writeByte(3); out.writeUTF(f.name()); out.writeInt(f.arguments().size());
                for (var arg : f.arguments()) expr(out,arg,depth+1);
            }
        }
    }
    private static Expr expr(DataInputStream in,int depth) throws IOException {
        if (depth>256) throw new IOException("scalar term depth limit");
        return switch(in.readUnsignedByte()) {
            case 0 -> new NumberExpr(ExactRational.fromCanonicalText(in.readUTF()));
            case 1 -> new VariableExpr(in.readUTF());
            case 2 -> { var op=BinaryOperator.valueOf(in.readUTF()); yield new BinaryExpr(expr(in,depth+1),op,expr(in,depth+1)); }
            case 3 -> {
                var name=in.readUTF(); int n=count(in,256); var args=new ArrayList<Expr>(n);
                for(int i=0;i<n;i++) args.add(expr(in,depth+1)); yield new FunctionExpr(name,args);
            }
            default -> throw new IOException("unknown scalar term kind");
        };
    }
}
