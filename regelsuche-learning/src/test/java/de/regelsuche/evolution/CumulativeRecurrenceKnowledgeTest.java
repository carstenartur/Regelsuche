package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Request;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Tests the existing knowledge lifecycle, not a parallel recurrence learner. */
@Timeout(180)
class CumulativeRecurrenceKnowledgeTest {
    private static int assertions;
    private static CumulativeTaskKnowledge empty() {
        return CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
    }
    private static Request request(int coefficient, long first, long second, int work) {
        return new Request(new Transition(new ExactMatrix(List.of(values(0,1), values(1,coefficient))),
            new ExactVector(values(0,0))), values(first,second), 2, new Budget(work));
    }
    private static List<Rational> values(long... values) {
        return java.util.Arrays.stream(values).mapToObj(Rational::of).toList();
    }
    private static RecurrenceKnowledge.Update learn(CumulativeTaskKnowledge parent, int coefficient) {
        return parent.learnRecurrences(List.of(request(coefficient,0,1,100_000)),
            List.of(request(coefficient,2,3,100_000)), new Budget(500_000));
    }
    private static void require(boolean value, String detail) {
        assertions++; if (!value) throw new AssertionError(detail);
    }

    @Test void acquiresAndReusesWithoutQuerySideLearning() {
        var base=empty(); var update=learn(base,1); var learned=update.next();
        require(update.promoted(), "validated recurrence capability is activated");
        require(base.recurrenceCount()==0 && learned.recurrenceCount()==1, "immutable predecessor");
        require(learned.parentId().equals(base.id()) && learned.generation()==1, "same generation chain");
        var task=request(1,2,1,100_000); String id=learned.id();
        var b=learned.solve(task,CumulativeTaskKnowledge.Strategy.BASELINE);
        var c=learned.solve(task,CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        require(b.solved() && c.solved() && !b.reused() && c.reused(), "baseline really synthesizes, learned route rechecks");
        require(c.totalWork()<b.totalWork(), "verified reuse actually saves work");
        require(c.law().orElseThrow().initialValue().equals(Rational.of(5)), "new initial state is not a cached answer");
        require(c.law().orElseThrow().valueAt(BigInteger.ONE).equals(Rational.of(-5)), "induction parity retained");
        require(learned.id().equals(id), "solve never acquires knowledge");
    }

    @Test void addsPellWithoutLosingFibonacciAndPreservesOldSnapshots() {
        var initial=empty(); String original=initial.toCanonicalJson();
        require(CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(),original).toCanonicalJson().equals(original),"v1 unchanged");
        var first=learn(initial,1).next(); var second=learn(first,2);
        require(second.promoted() && second.next().recurrenceCount()==2,"second generation adds different transition");
        require(second.next().parentId().equals(first.id()),"generation ancestry");
        for(int coefficient: new int[]{1,2}) require(second.next().solve(request(coefficient,5,7,100_000),
            CumulativeTaskKnowledge.Strategy.AUTOMATIC).reused(),"both acquired capabilities remain usable");
        var frozen=first.solve(request(2,5,7,100_000),CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        require(frozen.solved() && !frozen.reused(),"unknown transition falls back");
        var fresh=initial.solve(request(2,5,7,100_000),CumulativeTaskKnowledge.Strategy.BASELINE);
        require(frozen.totalWork()==fresh.totalWork()+6,"source mismatch pays only bounded routing, not a doomed proof");
        var restored=CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(),second.next().toCanonicalJson());
        require(restored.id().equals(second.next().id()) && restored.sessionSetupWork()>0,"v3 reload preserves identity and pays proofs");
    }

    @Test void keepsAcquisitionFailuresDuplicatesAndBudgetsVisible() {
        var initial=empty(); var limited=initial.learnRecurrences(List.of(request(1,0,1,100_000)),
            List.of(request(1,2,3,100_000)),new Budget(20));
        require(!limited.promoted() && limited.next().recurrenceCount()==0,"cutoff cannot publish partial mathematics");
        require(limited.work().total()>0 && limited.work().total()<=20,"failed acquisition stays paid without reset");
        var one=learn(initial,1).next(); var duplicate=learn(one,1);
        require(!duplicate.promoted() && duplicate.next().recurrenceCount()==1,"reuse is not falsely counted as a new law");
        require(duplicate.next().acquisitionWork()>one.acquisitionWork(),"duplicate lesson work retained");
        var normal=one.solve(request(1,5,7,100_000),CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        for(int cap: new int[]{0,1,(int)normal.totalWork()-1,(int)normal.totalWork()}) {
            var run=one.solve(request(1,5,7,cap),CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            require(run.totalWork()<=cap,"single query budget bounds routing and proof");
            require(run.solved()==(cap>=normal.totalWork()),"no unpaid final proof");
        }
        var lowDegree=request(1,5,7,100_000);
        var linear=new Request(lowDegree.transition(),lowDegree.initialState(),1,lowDegree.budget());
        require(!one.solve(linear,CumulativeTaskKnowledge.Strategy.AUTOMATIC).solved(),"quadratic law cannot satisfy a linear request");
    }

    @Test void rejectsSnapshotForgeryAndRequiresValidationCoverage() throws Exception {
        var one=learn(empty(),1).next();
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(one.toCanonicalJson());
        var entries=(com.fasterxml.jackson.databind.node.ArrayNode)json.readTree(root.get("recurrences").textValue());
        ((com.fasterxml.jackson.databind.node.ObjectNode)entries.get(0)).put("polynomial","x0^2+x1^2");
        root.put("recurrences",entries.toString());
        rejected(()->CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(),root.toString()));
        var missing=empty().learnRecurrences(List.of(request(1,0,1,100_000)),
            List.of(request(2,2,3,100_000)),new Budget(500_000));
        require(!missing.promoted() && missing.next().recurrenceCount()==0,"unvalidated transition cannot hitchhike");
        rejected(()->empty().learnRecurrences(List.of(request(1,0,1,100_000)),
            List.of(request(1,0,1,100_000)),new Budget(500_000)));
    }

    @Test void recurrenceAndExpressionLearningPreserveEachOther() {
        var parent=learn(empty(),1).next();
        var b=new de.regelsuche.search.moves.MoveSearch.Budget(32,32,200_000,512,200_000);
        java.util.function.Function<String,CheckedSchemaTaskSolver.Task> task=s->new CheckedSchemaTaskSolver.Task(
            new de.regelsuche.parse.ExpressionParser().parseExactTerm(s).expression(),List.of(),CheckedSchemaTaskSolver.Goal.atMostNodes(5),b);
        var expression=parent.learnBatch(List.of(task.apply("(a+b)*(a-b)+b*b+3"),task.apply("5*((c+d)*(c-d)+d*d)")),
            List.of(task.apply("(p*q+r)*(p*q-r)+r*r"),task.apply("(z+w)*(z-w)+w*w+7"),
                new CheckedSchemaTaskSolver.Task(new de.regelsuche.parse.ExpressionParser().parseExactTerm("x").expression(),List.of(),CheckedSchemaTaskSolver.Goal.atMostNodes(1),b),
                new CheckedSchemaTaskSolver.Task(new de.regelsuche.parse.ExpressionParser().parseExactTerm("(x+y)*(x-y)+y*(y+1)").expression(),List.of(),CheckedSchemaTaskSolver.Goal.atMostNodes(1),b)));
        require(expression.promoted() && expression.next().schemaCount()>0,"existing schema learner still works: "+expression.reason()+" "+expression.attempts());
        require(expression.next().recurrenceCount()==1,"expression generation preserves recurrence payload");
        var next=learn(expression.next(),2).next();
        require(next.schemaCount()==expression.next().schemaCount() && next.recurrenceCount()==2,"recurrence learning preserves schemas");
        var restored=CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(),next.toCanonicalJson());
        require(restored.schemaCount()==next.schemaCount() && restored.recurrenceCount()==2,"mixed snapshot rechecks both domains");
    }

    @Test void actualMixedStreamPaysForBothLearningRoundsAndReload() throws Exception {
        var directory=Files.createTempDirectory("recurrence-stream-");
        var base=empty();var first=learn(base,1).next();var second=learn(first,2).next();
        var frozen=CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(),first.save(directory));
        var cumulative=CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(),second.save(directory));
        long bw=0,fw=frozen.acquisitionWork()+frozen.sessionSetupWork(),cw=cumulative.acquisitionWork()+cumulative.sessionSetupWork();
        var rows=new ArrayList<String>();rows.add("round,case,baseline,frozen,cumulative,solved");
        int solved=0;
        for(int i=0;i<16;i++) for(int kind=0;kind<3;kind++) {
            Request task=kind<2 ? request(kind+1,i+3,i+5,100_000) : new Request(
                new Transition(new ExactMatrix(List.of(values(2))),new ExactVector(values(0))),values(i+1),2,new Budget(100_000));
            var b=base.solve(task,CumulativeTaskKnowledge.Strategy.BASELINE);
            var f=frozen.solve(task,CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            var c=cumulative.solve(task,CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            require(b.solved()==f.solved() && f.solved()==c.solved(),"same quality including negative cases");
            bw+=b.totalWork();fw+=f.totalWork();cw+=c.totalWork();if(c.solved())solved++;
            rows.add(i+","+kind+","+b.totalWork()+","+f.totalWork()+","+c.totalWork()+","+c.solved());
        }
        require(cw<bw && cw<fw,"actual task stream repays acquisition and revalidation");
        Path report=Path.of("build/reports/cumulative-recurrence-knowledge");Files.createDirectories(report);
        Files.write(report.resolve("mixed-stream.csv"),rows);
        String summary="queries=48 solvedEach="+solved+" baseline="+bw+" frozen="+fw+" cumulative="+cw
            +" acquisition="+cumulative.acquisitionWork()+" restoration="+cumulative.sessionSetupWork();
        Files.writeString(report.resolve("summary.txt"),summary+"\n");System.out.println(summary);
    }

    @Test void persistentGenerationsAreReusedInAnotherJvm() throws Exception {
        Path directory=Files.createTempDirectory("cumulative-recurrence-jvms-");
        String producer=runChild("save",directory,directory.resolve("save.log"));
        String consumer=runChild("restore",directory,directory.resolve("restore.log"));
        require(producer.contains("acquired=2"),"producer performed both learning rounds");
        require(consumer.contains("restored=2 reused=2"),"fresh consumer reused both capabilities without training");
    }

    @Test void unsolvedLessonsRemainPaidAndUseTheNewSnapshotRevision() {
        var transition=new Transition(new ExactMatrix(List.of(values(2))),new ExactVector(values(0)));
        var update=empty().learnRecurrences(List.of(new Request(transition,values(1),2,new Budget(100_000))),
            List.of(new Request(transition,values(2),2,new Budget(100_000))),new Budget(500_000));
        require(!update.promoted() && update.next().recurrenceCount()==0,"failed lesson cannot invent a theorem");
        require(update.work().total()>0 && update.reason()==CumulativeTaskKnowledge.Decision.NO_NEW_LAW,"negative attempt paid");
        require(update.next().toCanonicalJson().contains(CumulativeTaskKnowledge.RECURRENCE_REVISION),"new decision uses v3");
        require(CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(),update.next().toCanonicalJson()).id()
            .equals(update.next().id()),"empty recurrence catalog roundtrips with negative evidence");
    }

    @Test void additionalMalformedSnapshotsAndAcquisitionBoundariesFailClosed() throws Exception {
        var base=empty();var update=learn(base,1);var one=update.next();
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        for (String mutation: List.of("degree","evolution","linear","unknown","null","duplicate")) {
            var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(one.toCanonicalJson());
            var entries=(com.fasterxml.jackson.databind.node.ArrayNode)json.readTree(root.get("recurrences").textValue());
            var entry=(com.fasterxml.jackson.databind.node.ObjectNode)entries.get(0);
            switch(mutation) {
                case "degree" -> entry.put("degree",1);
                case "evolution" -> entry.put("evolution","FIXED");
                case "linear" -> ((com.fasterxml.jackson.databind.node.ArrayNode)entry.get("linear").get(1)).set(1,json.getNodeFactory().textNode("2"));
                case "unknown" -> entry.put("trusted",true);
                case "duplicate" -> entries.add(entry.deepCopy());
                case "null" -> { }
                default -> throw new AssertionError(mutation);
            }
            root.put("recurrences",mutation.equals("null") ? "null" : entries.toString());
            rejected(()->CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(),root.toString()));
        }
        int total=(int)update.work().total();
        for (int cap: new int[]{0,1,20,4350,4351,total-1,total,total+1}) {
            var limited=base.learnRecurrences(List.of(request(1,0,1,100_000)),List.of(request(1,2,3,100_000)),new Budget(cap));
            require(limited.work().total()<=cap,"acquisition budget remains shared at "+cap);
            require(limited.promoted()==(cap>=total),"activation requires all validation work at "+cap);
            if(cap<total) require(limited.reason()==CumulativeTaskKnowledge.Decision.ACQUISITION_LIMIT,"cutoff is not a proof of no benefit");
        }
    }

    private static void freshProcess(String mode, Path directory) throws Exception {
        if(mode.equals("save")) {
            var knowledge=learn(learn(empty(),1).next(),2).next();
            Files.writeString(directory.resolve("snapshot-name"),knowledge.save(directory).getFileName().toString());
            System.out.println("acquired="+knowledge.recurrenceCount());
        } else if(mode.equals("restore")) {
            var knowledge=CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(),
                directory.resolve(Files.readString(directory.resolve("snapshot-name"))));
            for(int coefficient: new int[]{1,2}) {
                var result=knowledge.solve(request(coefficient,2,1,100_000),CumulativeTaskKnowledge.Strategy.AUTOMATIC);
                require(result.solved() && result.reused(),"restored caller uses a checked law, not synthesis");
                require(result.law().orElseThrow().initialValue().equals(Rational.of(coefficient==1?5:7)),"new initial value");
            }
            System.out.println("restored="+knowledge.recurrenceCount()+" reused=2 setup="+knowledge.sessionSetupWork());
        } else throw new IllegalArgumentException("unknown process mode");
    }

    private static String runChild(String mode, java.nio.file.Path target, java.nio.file.Path output) throws Exception {
        var entries = new java.util.LinkedHashSet<String>();
        entries.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = CumulativeRecurrenceKnowledgeTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof java.net.URLClassLoader urls)
                for (var url : urls.getURLs()) entries.add(java.nio.file.Path.of(url.toURI()).toString());
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin",executable).toString(),
            "--enable-preview", "-cp", String.join(java.io.File.pathSeparator,entries), CumulativeRecurrenceKnowledgeTest.class.getName(), mode, target.toString())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            require(process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS), "child JVM must terminate");
            String text = java.nio.file.Files.readString(output);
            require(process.exitValue() == 0, "child failed: " + text);
            return text;
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS); }
        }
    }

    private static void rejected(Runnable action) {
        try{action.run();}catch(IllegalArgumentException expected){assertions++;return;}
        throw new AssertionError("expected rejection");
    }
    public static void main(String[] args) throws Exception {
        if(args.length==2) { freshProcess(args[0],Path.of(args[1])); return; }
        var t=new CumulativeRecurrenceKnowledgeTest();
        t.acquiresAndReusesWithoutQuerySideLearning();t.addsPellWithoutLosingFibonacciAndPreservesOldSnapshots();
        t.keepsAcquisitionFailuresDuplicatesAndBudgetsVisible();t.rejectsSnapshotForgeryAndRequiresValidationCoverage();
        t.recurrenceAndExpressionLearningPreserveEachOther();t.actualMixedStreamPaysForBothLearningRoundsAndReload();
        t.unsolvedLessonsRemainPaidAndUseTheNewSnapshotRevision();t.additionalMalformedSnapshotsAndAcquisitionBoundariesFailClosed();t.persistentGenerationsAreReusedInAnotherJvm();
        System.out.println("PASS cumulative recurrence assertions="+assertions);
    }
}
