package de.regelsuche.literature;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.literature.CandidateLiteratureAssessments.Assessment;
import de.regelsuche.literature.LiteratureReviewRepository.Subject;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Detached, offline review of a frozen scalar-equation candidate. Never promotes or publishes. */
public final class ExternalNoveltyReview {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
    private static final Pattern ROOT = Pattern.compile("sha256:[0-9a-f]{64}");
    public enum Disclosure { POST_FREEZE_ONLY }
    public enum Decision { PRIOR_ART_FOUND, NOVELTY_UNRESOLVED, NO_PRIOR_ART_FOUND_WITHIN_SCOPE, EXTERNALLY_NOVEL_CONFIRMED }
    public enum Vote { SUPPORT_SCOPED_NOVELTY, PRIOR_ART, ABSTAIN }

    public record Plan(Subject subject, String equation, List<String> assumptions, String formationEvidenceRoot,
                       String exposureLedgerRoot, Disclosure formationAccess, String frozenAt) {
        public Plan {
            Objects.requireNonNull(subject); Objects.requireNonNull(formationAccess); Instant.parse(frozenAt);
            assumptions = texts(assumptions,0,32); NoveltyQueryForms.forEquation(equation, assumptions);
            root(formationEvidenceRoot); root(exposureLedgerRoot);
        }
    }
    public record Source(String name,String version,String cataloguedOn,List<String> capabilities,
                         String accessConditions,String limitations) {
        public Source {
            text(name); text(version); LocalDate.parse(cataloguedOn);
            capabilities=texts(capabilities,1,12); text(accessConditions); text(limitations);
        }
    }
    public record Catalogue(String revision,List<Source> sources) {
        public Catalogue {
            text(revision); sources=bounded(sources,1,24);
            if(sources.stream().map(Source::name).distinct().count()!=sources.size()) throw bad("duplicate source");
        }
    }
    public record Query(@com.fasterxml.jackson.annotation.JsonProperty(required=true) int sourceIndex,String family,String expression,
                        String submittedText,String executedAt,
                        @com.fasterxml.jackson.annotation.JsonProperty(required=true) int resultCount,List<String> hitIds) {
        public Query {
            if(sourceIndex<0) throw bad("negative source index");
            text(family); text(expression); text(submittedText); Instant.parse(executedAt); hitIds=texts(hitIds,0,128);
            if (resultCount < hitIds.size()) throw bad("reported fewer results than observed identifiers");
            if(new HashSet<>(hitIds).size()!=hitIds.size()) throw bad("duplicate hit");
        }
    }
    public record QueryLog(String planRoot,String catalogueRoot,List<Query> entries) {
        public QueryLog { root(planRoot); root(catalogueRoot); entries=bounded(entries,0,192); }
    }
    public record Dossier(String logRoot,Assessment assessment) {
        public Dossier { root(logRoot); Objects.requireNonNull(assessment); }
    }
    /** Provided independently by the receiving custodian; private evidence stays outside the run. */
    public record Reviewer(String identityRoot,String publicKey,String qualificationRoot,String conflictClearanceRoot) {
        public Reviewer { root(identityRoot); text(publicKey); root(qualificationRoot); root(conflictClearanceRoot); }
    }
    public record Opinion(String packetRoot,String reviewerRoot,String qualificationRoot,String conflictClearanceRoot,
                          Vote vote,String rationale) {
        public Opinion {
            root(packetRoot); root(reviewerRoot); root(qualificationRoot); root(conflictClearanceRoot);
            Objects.requireNonNull(vote); text(rationale);
        }
    }
    public record SignedOpinion(Opinion opinion,String signature) {
        public SignedOpinion { Objects.requireNonNull(opinion); text(signature); }
    }
    public record Intake(String packetRoot,List<SignedOpinion> reviews) {
        public Intake { root(packetRoot); reviews=bounded(reviews,0,32); }
    }
    public record Packet(Plan plan,Catalogue catalogue,QueryLog log,Dossier dossier) { }
    public record Outcome(String packetRoot,String intakeRoot,Decision decision) { }
    public record Run(String schema,Packet packet,Intake intake,Outcome outcome) { }
    public static final String SCHEMA="regelsuche.external-novelty-run/v1";

    public static Catalogue scalarCatalogue() throws IOException {
        try (var input = ExternalNoveltyReview.class.getResourceAsStream("/literature/scalar-source-catalogue-v1.json")) {
            if (input == null) throw new IOException("missing scalar source catalogue");
            return JSON.readValue(input, Catalogue.class);
        }
    }

    public String freeze(Plan plan) { return identity("external-novelty-review-plan/v1",Objects.requireNonNull(plan)); }
    public String catalogueRoot(Catalogue catalogue) { return identity("external-source-catalog/v1",Objects.requireNonNull(catalogue)); }
    public String logRoot(QueryLog log) { return identity("external-search-log/v1",Objects.requireNonNull(log)); }
    public String packetRoot(Packet packet) {
        NoveltyCoverage.check(this,packet);
        return identity("external-review-packet/v1",packet);
    }
    public byte[] signingBytes(Opinion opinion) { return bytes("external-review-opinion/v1",opinion); }

    public Run evaluate(String expectedFreeze,String expectedCatalogue,Packet packet,Intake intake,List<Reviewer> eligible) {
        if(!freeze(packet.plan()).equals(expectedFreeze)||!catalogueRoot(packet.catalogue()).equals(expectedCatalogue))
            throw bad("review does not bind the receiver's expected freeze/catalogue");
        String id=packetRoot(packet);
        if(!id.equals(intake.packetRoot())) throw bad("intake attached to another packet");
        var votes=NoveltySignatures.verify(this,id,intake,bounded(eligible,0,128));
        var outcome=new Outcome(id,identity("external-review-intake/v1",intake),decide(packet,votes,eligible.size()));
        return new Run(SCHEMA,packet,intake,outcome);
    }
    private static Decision decide(Packet packet,List<Vote> votes,int assigned) {
        var status=packet.dossier().assessment().status();
        if(status==CandidateLiteratureAssessments.Status.PRIOR_ART_FOUND) return Decision.PRIOR_ART_FOUND;
        if(status!=CandidateLiteratureAssessments.Status.NO_PRIOR_ART_FOUND_WITHIN_SCOPE
                || votes.stream().anyMatch(v->v!=Vote.SUPPORT_SCOPED_NOVELTY)) return Decision.NOVELTY_UNRESOLVED;
        if (!votes.isEmpty() && votes.size() < assigned && votes.size() >= 2) return Decision.NOVELTY_UNRESOLVED;
        return votes.size()>=2 && votes.size()==assigned ? Decision.EXTERNALLY_NOVEL_CONFIRMED : Decision.NO_PRIOR_ART_FOUND_WITHIN_SCOPE;
    }
    public byte[] encode(Run run) throws IOException {
        byte[] encoded=JSON.writeValueAsBytes(run);
        if(encoded.length>1024*1024) throw bad("run too large");
        return encoded;
    }
    public Run replay(String expectedFreeze,String expectedCatalogue,List<Reviewer> eligible,byte[] encoded) throws IOException {
        if(encoded.length>1024*1024) throw bad("run too large");
        Run run=JSON.readValue(encoded,Run.class);
        if(!SCHEMA.equals(run.schema())) throw bad("unsupported review run");
        Run verified=evaluate(expectedFreeze,expectedCatalogue,run.packet(),run.intake(),eligible);
        if(!verified.equals(run)) throw bad("claimed review decision failed replay");
        return verified;
    }
    /** Produce the seven private records after independently replaying the full submission. */
    public Map<String,byte[]> artifacts(String expectedFreeze,String expectedCatalogue,List<Reviewer> eligible,byte[] submitted) throws IOException {
        var run=replay(expectedFreeze,expectedCatalogue,eligible,submitted);
        var p=run.packet();
        var records=new LinkedHashMap<String,byte[]>();
        records.put("external-novelty-review-plan.json",bytes("external-novelty-review-plan/v1",p.plan()));
        records.put("external-source-catalog.json",bytes("external-source-catalog/v1",p.catalogue()));
        records.put("external-search-log.json",bytes("external-search-log/v1",p.log()));
        records.put("external-match-dossier.json",bytes("external-match-dossier/v1",p.dossier()));
        records.put("external-review-intake.json",bytes("external-review-intake/v1",run.intake()));
        records.put("external-novelty-decision.json",bytes("external-novelty-decision/v1",run.outcome()));
        records.put("external-novelty-run.json",encode(run));
        return Collections.unmodifiableMap(records);
    }
    static String identity(String type,Object data) {
        try { return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(type,data))); }
        catch(GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private static byte[] bytes(String type,Object data) {
        try { return JSON.writeValueAsBytes(List.of(type,data)); }
        catch(IOException e) { throw new IllegalArgumentException("invalid review data",e); }
    }
    static void text(String value) { if(value==null||value.isBlank()||value.length()>3000) throw bad("invalid review text"); }
    static void root(String value) { if(value==null||!ROOT.matcher(value).matches()) throw bad("invalid content identity"); }
    static <T> List<T> bounded(List<T> values,int min,int max) {
        if(values==null||values.size()<min||values.size()>max) throw bad("invalid list size");
        return List.copyOf(values);
    }
    private static List<String> texts(List<String> values,int min,int max) {
        var list=bounded(values,min,max); list.forEach(ExternalNoveltyReview::text); return list;
    }
    static IllegalArgumentException bad(String reason) { return new IllegalArgumentException(reason); }
}
