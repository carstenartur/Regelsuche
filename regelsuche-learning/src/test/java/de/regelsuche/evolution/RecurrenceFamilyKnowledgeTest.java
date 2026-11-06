package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Request;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;

@Timeout(180)
class RecurrenceFamilyKnowledgeTest {
    private static int assertions;
    private static final Budget ACQUISITION = new Budget(500_000);
    private static List<Rational> values(long... n) { return java.util.Arrays.stream(n).mapToObj(Rational::of).toList(); }
    private static Request request(long parameter, long a, long b) { return request(Rational.of(parameter),a,b); }
    private static Request request(Rational parameter, long a, long b) {
        return new Request(new Transition(new ExactMatrix(List.of(values(0,1), List.of(Rational.ONE,parameter))),
            new ExactVector(values(0,0))), values(a,b), 2, new Budget(100_000));
    }
    private static CumulativeTaskKnowledge empty() { return CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory()); }
    private static CumulativeTaskKnowledge seed() {
        var k=empty();
        for (int p : new int[]{1,2}) {
            var u=k.learnRecurrences(List.of(request(p,0,1)),List.of(request(p,2,3)),ACQUISITION);
            require(u.promoted(), "source law acquired"); k=u.next();
        }
        return k;
    }
    @Test void reusesTheUniversalStepInsteadOfReprovingItForEveryInitialState() {
        var known=family();
        var task=request(7,3,4);
        var run=known.solve(task,CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        require(run.solved() && run.reused(), "actual acquired family application required");
        var audit=new de.regelsuche.math.algorithms.linalg.RecurrenceInvariantVerifier().verify(
            task.transition(),run.law().orElseThrow().proposal(),task.initialState(),task.budget());
        require(audit.law().isPresent(), "original full verifier independently accepts the instance");
        require(audit.law().get().initialValue().equals(run.law().get().initialValue()), "new initial value is independently recomputed");
        System.out.println("proofReuseApplication="+run.totalWork()+" fullStepReplay="+audit.work().consumedWorkUnits());
        require(run.totalWork()<audit.work().consumedWorkUnits(),
            "the acquired universal proof must replace repeated step expansion: application="+run.totalWork()
                +", full-step-audit="+audit.work().consumedWorkUnits());
    }

    @Test void transfersAnAcquiredRelationshipToAnUnseenTransition() {
        var exact=seed();
        var update=exact.generalizeRecurrences(List.of(request(3,2,3)),ACQUISITION);
        require(update.promoted(), "unseen validation activates the proved family: "+update.reason());
        var generalized=update.next();
        require(generalized.recurrenceFamilyCount()==1 && exact.recurrenceFamilyCount()==0,"new generation not query-side learning");
        var result=generalized.solve(request(7,3,4), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        require(result.solved() && result.reused(), "unseen transition must reuse generalized knowledge, not solve its coefficient system again");
        require(result.law().orElseThrow().initialValue().abs().equals(Rational.of(77)), "new parameter and initial state");
    }
    private static RecurrenceKnowledge.Update generalize(CumulativeTaskKnowledge k, int budget) {
        return k.generalizeRecurrences(List.of(request(3,2,3),request(5,3,4)),new Budget(budget));
    }
    private static CumulativeTaskKnowledge family() {
        var update=generalize(seed(),500_000);
        require(update.promoted(),"two stored laws generalized on separate transitions");
        return update.next();
    }
    @Test void requiresNewTransitionValidationAndKeepsFailedAttemptsPaid() {
        var exact=seed();
        var repeated=exact.generalizeRecurrences(List.of(request(1,5,7),request(2,5,7)),ACQUISITION);
        require(!repeated.promoted() && repeated.next().recurrenceFamilyCount()==0,"new initial states are not new transition validation");
        require(repeated.work().total()>0 && repeated.next().acquisitionWork()>exact.acquisitionWork(),"declined acquisition paid");
        var capped=generalize(exact,20);
        require(!capped.promoted() && capped.reason()==CumulativeTaskKnowledge.Decision.ACQUISITION_LIMIT,"bounded generalization");
        require(capped.work().total()<=20 && capped.work().total()>0,"no acquisition budget reset");
        var one=empty().learnRecurrences(List.of(request(1,0,1)),List.of(request(1,2,3)),ACQUISITION).next();
        require(!generalize(one,500_000).promoted(),"one example cannot establish parameter dependence");
        var ready=family(); var duplicate=generalize(ready,500_000);
        require(!duplicate.promoted() && duplicate.next().recurrenceFamilyCount()==1,"no duplicate family accumulation");
        var lesson=ready.learnRecurrences(List.of(request(7,0,1)),List.of(request(7,2,3)),ACQUISITION);
        require(!lesson.promoted() && lesson.training().getFirst().reused(),"family solves a new lesson without inventing a duplicate exact rule");
    }
    @Test void preservesV3AndRechecksUntrustedV4Snapshots() throws Exception {
        var exact=seed();var exactJson=exact.toCanonicalJson();
        var restoredExact=CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(),exactJson);
        require(restoredExact.toCanonicalJson().equals(exactJson) && restoredExact.recurrenceFamilyCount()==0,"old v3 bytes unchanged");
        var known=family();var json=known.toCanonicalJson();
        require(json.contains(CumulativeTaskKnowledge.RECURRENCE_FAMILY_REVISION),"family snapshots have their own revision");
        var restored=CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(),json);
        require(restored.id().equals(known.id()) && restored.sessionSetupWork()>0,"rebuild and reprove family from its untrusted anchors");
        require(restored.solve(request(-3,5,7),CumulativeTaskKnowledge.Strategy.AUTOMATIC).reused(),"reloaded family transfers");
        rejected(()->CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(),
            json.replace(CumulativeTaskKnowledge.RECURRENCE_FAMILY_REVISION,CumulativeTaskKnowledge.RECURRENCE_REVISION)));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(json);
        var entries=(com.fasterxml.jackson.databind.node.ArrayNode)mapper.readTree(root.get("recurrences").textValue());
        var family=(com.fasterxml.jackson.databind.node.ObjectNode)entries.get(2);
        var second=(com.fasterxml.jackson.databind.node.ObjectNode)family.get("family");
        second.put("polynomial","x0^2+x1^2"); root.put("recurrences",entries.toString());
        rejected(()->CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(),root.toString()));
        // Both substituted anchors below are TRUE, but their interpolated family is FALSE.
        family.put("polynomial","x0^2").put("evolution","FIXED");
        family.set("linear",mapper.readTree("[[\"1\"]]"));family.set("offset",mapper.readTree("[\"0\"]"));
        second.put("polynomial","x0^2").put("evolution","FIXED");
        second.set("linear",mapper.readTree("[[\"-1\"]]"));second.set("offset",mapper.readTree("[\"0\"]"));
        root.put("recurrences",entries.toString());
        rejected(()->CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(),root.toString()));
    }
    @Test void paysForSpecializationFinalVerificationAndFallback() {
        var known=family();var task=request(7,3,4);
        var full=known.solve(task,CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        for(int limit:new int[]{0,1,(int)full.totalWork()-1,(int)full.totalWork()}) {
            var limited=new Request(task.transition(),task.initialState(),task.degree(),new Budget(limit));
            var run=known.solve(limited,CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            require(run.totalWork()<=limit,"single budget includes matching, specialization and proof");
            require(run.solved()==(limit>=full.totalWork()),"proof cannot become free on cutoff");
        }
        var wrong=new Request(new Transition(new ExactMatrix(List.of(values(1,1),values(1,7))),new ExactVector(values(0,0))),values(3,4),2,new Budget(100_000));
        var b=empty().solve(wrong,CumulativeTaskKnowledge.Strategy.BASELINE);
        var automatic=known.solve(wrong,CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        require(!automatic.reused() && automatic.solved()==b.solved(),"fixed source components remain binding and fallback is preserved");
        var linear=new Request(task.transition(),task.initialState(),1,task.budget());
        require(!known.solve(linear,CumulativeTaskKnowledge.Strategy.AUTOMATIC).solved(),"quadratic family cannot satisfy linear goal");
    }
    @Test void expressionLearningPreservesTheFamilyPayload() {
        var known=family();
        var budget=new de.regelsuche.search.moves.MoveSearch.Budget(32,32,100_000,512,100_000);
        java.util.function.Function<String,CheckedSchemaTaskSolver.Task> task=e->new CheckedSchemaTaskSolver.Task(
            new de.regelsuche.parse.ExpressionParser().parseExactTerm(e).expression(),List.of(),CheckedSchemaTaskSolver.Goal.atMostNodes(1),budget);
        var next=known.learnBatch(List.of(task.apply("x"),task.apply("x+1")),List.of(task.apply("x*x"))).next();
        require(next.recurrenceFamilyCount()==1 && next.solve(request(11,3,4),CumulativeTaskKnowledge.Strategy.AUTOMATIC).reused(),"expression generation retains the acquired family");
        require(CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(),next.toCanonicalJson()).id().equals(next.id()),"mixed snapshot remains canonical");
    }
    @Test void actualUnseenTransitionStreamRepaysGeneralizationAndReload() throws Exception {
        var base=empty();var exact=seed();var update=generalize(exact,500_000);require(update.promoted(),"family admitted");
        var directory=Files.createTempDirectory("family-stream-");
        var frozen=CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(),exact.save(directory));
        var learned=CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(),update.next().save(directory));
        long bw=0,fw=frozen.acquisitionWork()+frozen.sessionSetupWork(),lw=learned.acquisitionWork()+learned.sessionSetupWork(),queries=0;
        int solved=0,reused=0;
        var rows=new java.util.ArrayList<String>();rows.add("round,parameter,baseline,exactOnly,family,solved,reused");
        var parameters=List.of(Rational.of(4),Rational.of(7),Rational.of(-3),Rational.ZERO,Rational.of(11),Rational.of(-7),new Rational(BigInteger.ONE,BigInteger.TWO));
        String id=learned.id();
        for(int round=0;round<8;round++) for(int kind=0;kind<=parameters.size();kind++) {
            var task=kind<parameters.size()?request(parameters.get(kind),round+2,round*2+3):new Request(
                new Transition(new ExactMatrix(List.of(values(2))),new ExactVector(values(0))),values(round+1),2,new Budget(100_000));
            var b=base.solve(task,CumulativeTaskKnowledge.Strategy.BASELINE);
            var f=frozen.solve(task,CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            var l=learned.solve(task,CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            require(b.solved()==f.solved() && f.solved()==l.solved(),"same task success including unrelated negative controls");
            require(!f.reused(),"exact-only memory cannot match any test transition");
            bw+=b.totalWork();fw+=f.totalWork();lw+=l.totalWork();queries+=l.totalWork();
            if(l.solved()) solved++;if(l.reused())reused++;
            rows.add(round+","+(kind<parameters.size()?parameters.get(kind):"unrelated")+","+b.totalWork()+","+f.totalWork()+","+l.totalWork()+","+l.solved()+","+l.reused());
        }
        require(id.equals(learned.id()),"no query cache or query-side learning");
        require(solved==56 && reused==56,"unseen coefficients transferred; eight negatives retained");
        require(lw<bw && lw<fw,"full declared stream pays for both source lessons, family formation, validation and reload");
        var report=Path.of("build/reports/recurrence-family-learning");Files.createDirectories(report);
        Files.write(report.resolve("unseen-stream.csv"),rows);
        String summary="queries=64 solvedEach="+solved+" reusedFamily="+reused+" baseline="+bw+" exactOnly="+fw+" family="+lw
            +" acquisition="+learned.acquisitionWork()+" restoration="+learned.sessionSetupWork()+" queryWork="+queries+" familyAcquisition="+update.work().total();
        Files.writeString(report.resolve("summary.txt"),summary+"\n");System.out.println(summary);
    }
    @Test void persistenceTransfersInAFreshJvmWithoutSourceTraining() throws Exception {
        var directory=Files.createTempDirectory("family-jvms-");
        require(runChild("save",directory).contains("familySaved=1"),"producer actually acquired a family");
        require(runChild("restore",directory).contains("unseenReused=1"),"separate consumer transfers without training");
    }
    private static void process(String mode,Path directory) throws Exception {
        if(mode.equals("save")) {
            var k=family();Files.writeString(directory.resolve("snapshot"),k.save(directory).getFileName().toString());
            System.out.println("familySaved="+k.recurrenceFamilyCount());
        } else if(mode.equals("restore")) {
            var k=CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(),directory.resolve(Files.readString(directory.resolve("snapshot"))));
            var result=k.solve(request(7,3,4),CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            require(result.reused() && result.law().orElseThrow().initialValue().abs().equals(Rational.of(77)),"new JVM must recheck the unseen transition");
            System.out.println("unseenReused=1 setup="+k.sessionSetupWork());
        } else throw new IllegalArgumentException("unknown child mode");
    }
    private static String runChild(String mode,Path directory) throws Exception {
        var entries=new java.util.LinkedHashSet<String>();entries.add(System.getProperty("java.class.path"));
        for(ClassLoader loader=RecurrenceFamilyKnowledgeTest.class.getClassLoader();loader!=null;loader=loader.getParent())
            if(loader instanceof java.net.URLClassLoader urls) for(var url:urls.getURLs())entries.add(Path.of(url.toURI()).toString());
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        var output=directory.resolve(mode+".log");
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"--enable-preview","-cp",
            String.join(java.io.File.pathSeparator,entries),RecurrenceFamilyKnowledgeTest.class.getName(),mode,directory.toString())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            require(child.waitFor(45,java.util.concurrent.TimeUnit.SECONDS),"bounded child execution");
            String text=Files.readString(output);require(child.exitValue()==0,"child failure: "+text);return text;
        } finally { if(child.isAlive()) { child.destroyForcibly();child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS); } }
    }
    private static void rejected(Runnable call) {
        try { call.run(); } catch(IllegalArgumentException expected) { assertions++;return; }
        throw new AssertionError("untrusted family should be rejected");
    }
    private static void require(boolean ok, String detail) { assertions++; if (!ok) throw new AssertionError(detail); }
    public static void main(String[] args) throws Exception {
        if(args.length==2) { process(args[0],Path.of(args[1]));return; }
        var t=new RecurrenceFamilyKnowledgeTest();
        t.reusesTheUniversalStepInsteadOfReprovingItForEveryInitialState();
        t.transfersAnAcquiredRelationshipToAnUnseenTransition();
        t.requiresNewTransitionValidationAndKeepsFailedAttemptsPaid();
        t.preservesV3AndRechecksUntrustedV4Snapshots();
        t.paysForSpecializationFinalVerificationAndFallback();
        t.expressionLearningPreservesTheFamilyPayload();
        t.actualUnseenTransitionStreamRepaysGeneralizationAndReload();
        t.persistenceTransfersInAFreshJvmWithoutSourceTraining();
        System.out.println("PASS recurrence-family assertions="+assertions);
    }
}
