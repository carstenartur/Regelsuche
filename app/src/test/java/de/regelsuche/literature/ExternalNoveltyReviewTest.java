package de.regelsuche.literature;

import static de.regelsuche.literature.ExternalNoveltyReview.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.literature.CandidateLiteratureAssessments.*;
import de.regelsuche.literature.LiteratureReviewRepository.Subject;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExternalNoveltyReviewTest {
    final ExternalNoveltyReview review = new ExternalNoveltyReview();
    static String hash(String c) { return "sha256:" + c.repeat(64); }
    final Subject subject = new Subject(hash("1"),hash("2"),hash("3"));
    final Plan plan = new Plan(subject,"x + y = y + x",List.of("real x,y"),hash("4"),hash("5"),
        Disclosure.POST_FREEZE_ONLY,"2026-11-01T12:00:00Z");
    final Catalogue catalog = new Catalogue("fixture/v1",List.of(new Source("fixture","v1","2026-10-30",
        List.of("text search"),"offline","single synthetic record")));
    final Map<String,String> forms = NoveltyQueryForms.forEquation(plan.equation(), plan.assumptions());
    Packet packet(Status status, Access access, boolean hit, boolean unresolved) {
        var observed=hits(hit,unresolved);
        var rows=access==Access.INACCESSIBLE ? List.<Query>of() : forms.entrySet().stream().map(e->
            new Query(0,e.getKey(),e.getValue(),e.getValue(),"2026-11-01T13:00:00Z",observed.size(),observed)).toList();
        var log=new QueryLog(review.freeze(plan),review.catalogueRoot(catalog),rows);
        var scope=new Scope("fixture","v1",new Coverage(access,"scope bound",
            unresolved?List.of(new UnresolvedHit("ref2","full text missing")):List.of()));
        var source=new RuleLiteratureCatalog.Source("test",List.of("Fixture"),"Example",null,"ref1","v1","p.1","https://example.org/a");
        var matches=hit?List.of(new Match(source,RuleLiteratureCatalog.Relation.EQUIVALENT,List.of("real x,y"),
            "The same equation with the sides reversed.",null,0)):List.<Match>of();
        var a=new Assessment(CandidateLiteratureAssessments.COVERAGE_SCHEMA,subject,"2026-11-01","test intake",
            List.of(scope),rows.stream().map(Query::expression).distinct().toList(),status,matches);
        var inspected = hit ? List.of(new InspectedHit(0,"ref1",PriorArtRelation.SEMANTICALLY_EQUIVALENT_PRIOR_ART,
            "p.1, Example: for real x and y, addition commutes.", hash("c"))) : List.<InspectedHit>of();
        return new Packet(plan,catalog,log,new Dossier(review.logRoot(log),a,inspected));
    }
    static List<String> hits(boolean hit,boolean gap) {
        var result=new ArrayList<String>();if(hit) result.add("ref1");if(gap) result.add("ref2");return result;
    }
    record Signer(KeyPair key, Reviewer eligible) { }
    static Signer signer(String identity) throws Exception {
        var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return new Signer(key,new Reviewer(hash(identity),Base64.getEncoder().encodeToString(key.getPublic().getEncoded()),hash("a"),hash("b")));
    }
    SignedOpinion sign(Packet p,Signer signer,Vote vote) throws Exception {
        var r=signer.eligible();var opinion=new Opinion(review.packetRoot(p),r.identityRoot(),
            r.qualificationRoot(),r.conflictClearanceRoot(),vote,"fixture reasoning");
        var signature=Signature.getInstance("Ed25519");signature.initSign(signer.key().getPrivate());
        signature.update(review.signingBytes(opinion));return new SignedOpinion(opinion,Base64.getEncoder().encodeToString(signature.sign()));
    }
    Run decide(Packet p,List<Reviewer> elig,SignedOpinion... votes) {
        return review.evaluate(review.freeze(plan),review.catalogueRoot(catalog),p,new Intake(review.packetRoot(p),List.of(votes)),elig);
    }
    @Test void detachedReceiverBindsFourOfflineQueryFormsAndFullCandidate() {
        assertEquals(Set.of("EXACT","ALPHA","STRUCTURAL","EQUIVALENT","POLYNOMIAL_NORMAL_FORM","CLASSIFICATION","ASSUMPTIONS"),forms.keySet());
        assertEquals("((v0+v1)=(v1+v0))",forms.get("ALPHA"));
        assertEquals("y + x = x + y",forms.get("EQUIVALENT"));
        var p=packet(Status.NO_PRIOR_ART_FOUND_WITHIN_SCOPE,Access.SEARCHED,false,false);
        var other=new Plan(subject,plan.equation(),List.of("integer x,y"),plan.formationEvidenceRoot(),
            plan.exposureLedgerRoot(),plan.formationAccess(),plan.frozenAt());
        assertThrows(IllegalArgumentException.class,()->review.evaluate(review.freeze(other),review.catalogueRoot(catalog),
            p,new Intake(review.packetRoot(p),List.of()),List.of()));
    }
    @Test void twoEligibleSignaturesAndConservativeOutcomesReplay() throws Exception {
        var p=packet(Status.NO_PRIOR_ART_FOUND_WITHIN_SCOPE,Access.SEARCHED,false,false);
        var a=signer("6");var b=signer("7");var elig=List.of(a.eligible(),b.eligible());
        var yes=sign(p,a,Vote.SUPPORT_SCOPED_NOVELTY);
        assertEquals(Decision.NO_PRIOR_ART_FOUND_WITHIN_SCOPE,decide(p,elig,yes).outcome().decision());
        assertEquals(Decision.NOVELTY_UNRESOLVED,decide(p,elig,yes,sign(p,b,Vote.ABSTAIN)).outcome().decision());
        var accepted=decide(p,elig,yes,sign(p,b,Vote.SUPPORT_SCOPED_NOVELTY));
        assertEquals(Decision.EXTERNALLY_NOVEL_CONFIRMED,accepted.outcome().decision());
        var third=signer("8");
        assertEquals(Decision.NO_PRIOR_ART_FOUND_WITHIN_SCOPE,decide(p,List.of(a.eligible(),b.eligible(),third.eligible()),yes,
            sign(p,b,Vote.SUPPORT_SCOPED_NOVELTY)).outcome().decision());
        var receiver=new ExternalNoveltyReview();var encoded=review.encode(accepted);
        assertEquals(accepted,receiver.replay(review.freeze(plan),review.catalogueRoot(catalog),elig,encoded));
        assertEquals(7,receiver.artifacts(review.freeze(plan),review.catalogueRoot(catalog),elig,encoded).size());
        assertThrows(IllegalArgumentException.class,()->receiver.replay(review.freeze(plan),review.catalogueRoot(catalog),List.of(),encoded));
        var forged=new Run(SCHEMA,p,accepted.intake(),new Outcome(yes.opinion().packetRoot(),hash("8"),Decision.PRIOR_ART_FOUND));
        assertThrows(IllegalArgumentException.class,()->receiver.replay(review.freeze(plan),review.catalogueRoot(catalog),elig,review.encode(forged)));
    }
    @Test void incompleteAndEquivalentSourceEvidenceCannotSupportANoveltyFinding() throws Exception {
        var prior=packet(Status.PRIOR_ART_FOUND,Access.SEARCHED,true,false);
        assertEquals(Decision.PRIOR_ART_FOUND,decide(prior,List.of()).outcome().decision());
        for(var p:List.of(packet(Status.UNRESOLVED,Access.INACCESSIBLE,false,false),packet(Status.UNRESOLVED,Access.PARTIAL,false,true))) {
            var a=signer("6");var b=signer("7");
            assertEquals(Decision.NOVELTY_UNRESOLVED,decide(p,List.of(a.eligible(),b.eligible()),
                sign(p,a,Vote.SUPPORT_SCOPED_NOVELTY),sign(p,b,Vote.SUPPORT_SCOPED_NOVELTY)).outcome().decision());
        }
    }
    @Test void datedCatalogueAndIncompleteResultPagesAreChecked() throws Exception {
        var supplied = ExternalNoveltyReview.scalarCatalogue();
        assertEquals(List.of("NIST DLMF", "mathlib4"), supplied.sources().stream().map(Source::name).toList());
        assertEquals("x+y=x+y", forms.get("POLYNOMIAL_NORMAL_FORM").replace(" ", ""));
        assertTrue(forms.get("ASSUMPTIONS").contains("real x,y"));
        var packet = packet(Status.NO_PRIOR_ART_FOUND_WITHIN_SCOPE,Access.SEARCHED,false,false);
        var first = packet.log().entries().getFirst();
        var rows = new ArrayList<>(packet.log().entries());
        rows.set(0, new Query(0,first.family(),first.expression(),first.submittedText(),first.executedAt(),12,List.of()));
        var log = new QueryLog(packet.log().planRoot(),packet.log().catalogueRoot(),rows);
        var dossier = packet.dossier().assessment();
        assertThrows(IllegalArgumentException.class, () -> review.packetRoot(
            new Packet(plan,catalog,log,new Dossier(review.logRoot(log),dossier,packet.dossier().inspected()))));
        var future = new Catalogue("future",List.of(new Source("fixture","v1","2026-11-02", List.of("text"),"offline","fixture")));
        var retro = new QueryLog(packet.log().planRoot(),review.catalogueRoot(future),packet.log().entries());
        assertThrows(IllegalArgumentException.class, () -> review.packetRoot(
            new Packet(plan,future,retro,new Dossier(review.logRoot(retro),dossier,packet.dossier().inspected()))));
    }
    @Test void packetKeyConflictsChangedTrustOrDuplicatedPersonsAreRejected() throws Exception {
        var p=packet(Status.NO_PRIOR_ART_FOUND_WITHIN_SCOPE,Access.SEARCHED,false,false);
        var s=signer("6");var member=s.eligible();var signed=sign(p,s,Vote.SUPPORT_SCOPED_NOVELTY);
        var eligible=List.of(member);
        assertThrows(IllegalArgumentException.class,()->decide(p,eligible,signed,signed));
        var alias=new Reviewer(hash("7"),member.publicKey(),member.qualificationRoot(),member.conflictClearanceRoot());
        assertThrows(IllegalArgumentException.class,()->decide(p,List.of(member,alias),signed));
        var changed=new Reviewer(member.identityRoot(),member.publicKey(),hash("8"),member.conflictClearanceRoot());
        assertThrows(IllegalArgumentException.class,()->decide(p,List.of(changed),signed));
        var forged=new SignedOpinion(new Opinion(review.packetRoot(p),member.identityRoot(),member.qualificationRoot(),
            member.conflictClearanceRoot(),Vote.ABSTAIN,"other rationale"),signed.signature());
        assertThrows(IllegalArgumentException.class,()->decide(p,eligible,forged));
        var other=packet(Status.UNRESOLVED,Access.INACCESSIBLE,false,false);
        assertThrows(IllegalArgumentException.class,()->decide(other,eligible,signed));
    }
    @Test void everyReviewedReferenceRequiresItsOwnInspectedContextAndRelation() {
        var p = packet(Status.PRIOR_ART_FOUND,Access.SEARCHED,true,false);
        var dossier = p.dossier();
        var hit = dossier.inspected().getFirst();
        var wrongRelation = new InspectedHit(0,"ref1",PriorArtRelation.RELATED_NOT_EQUIVALENT,
            hit.inspectedContext(),hit.evidenceRoot());
        var wrongSource = new InspectedHit(0,"different",hit.relation(),hit.inspectedContext(),hit.evidenceRoot());
        for (List<InspectedHit> entries : List.of(List.<InspectedHit>of(),List.of(wrongRelation),
                List.of(wrongSource),List.of(hit,hit))) {
            var changed = new Dossier(dossier.logRoot(),dossier.assessment(),entries);
            assertThrows(IllegalArgumentException.class, () -> review.packetRoot(new Packet(plan,catalog,p.log(),changed)));
        }
        var alpha = new InspectedHit(0,"ref1",PriorArtRelation.ALPHA_EQUIVALENT_PRIOR_ART,
            "The cited variables are renamed consistently.",hash("d"));
        var recoded = new Dossier(dossier.logRoot(),dossier.assessment(),List.of(alpha));
        assertNotEquals(review.packetRoot(p),review.packetRoot(new Packet(plan,catalog,p.log(),recoded)));
    }

    @Test void missingApprovalsDoNotTurnScopedAbsenceIntoUnresolvedAtTwoOfThree() throws Exception {
        var p = packet(Status.NO_PRIOR_ART_FOUND_WITHIN_SCOPE, Access.SEARCHED, false, false);
        var first = signer("6");
        var second = signer("7");
        var eligible = List.of(first.eligible(), second.eligible(), signer("8").eligible());
        var recommendation = Decision.NO_PRIOR_ART_FOUND_WITHIN_SCOPE;
        assertEquals(recommendation, decide(p, eligible).outcome().decision());
        assertEquals(recommendation, decide(p, eligible, sign(p,first,Vote.SUPPORT_SCOPED_NOVELTY)).outcome().decision());
        assertEquals(recommendation, decide(p, eligible, sign(p,first,Vote.SUPPORT_SCOPED_NOVELTY),
            sign(p,second,Vote.SUPPORT_SCOPED_NOVELTY)).outcome().decision());
    }

    @Test void approvedSourceTranslationIsBoundAtFreezeAndUnknownTranslationsStayPartial() throws Exception {
        var base = packet(Status.NO_PRIOR_ART_FOUND_WITHIN_SCOPE,Access.SEARCHED,false,false);
        var first = base.log().entries().getFirst();
        var source = catalog.sources().getFirst();
        String translated = "#find \u2200 (x y : \u211d), x + y = y + x";
        var approval = new Translation(source.name(),source.version(),first.family(),first.expression(),translated,
            "Both sides of the equality use commutative addition of real variables.",hash("9"));
        var trustedPlan = new Plan(subject,plan.equation(),plan.assumptions(),plan.formationEvidenceRoot(),
            plan.exposureLedgerRoot(),plan.formationAccess(),plan.frozenAt(),List.of(approval));
        var rows = new ArrayList<>(base.log().entries());
        rows.set(0,new Query(0,first.family(),first.expression(),translated,first.executedAt(),0,List.of()));
        var translatedLog = new QueryLog(review.freeze(trustedPlan),review.catalogueRoot(catalog),rows);
        var p = new Packet(trustedPlan,catalog,translatedLog,
            new Dossier(review.logRoot(translatedLog),base.dossier().assessment(),List.of()));
        var a=signer("6"); var b=signer("7"); var roster=List.of(a.eligible(),b.eligible());
        var intake=new Intake(review.packetRoot(p),List.of(sign(p,a,Vote.SUPPORT_SCOPED_NOVELTY),sign(p,b,Vote.SUPPORT_SCOPED_NOVELTY)));
        var run=review.evaluate(review.freeze(trustedPlan),review.catalogueRoot(catalog),p,intake,roster);
        assertEquals(Decision.EXTERNALLY_NOVEL_CONFIRMED,run.outcome().decision());
        assertEquals(run,review.replay(review.freeze(trustedPlan),review.catalogueRoot(catalog),roster,review.encode(run)));
        assertThrows(IllegalArgumentException.class,()->review.replay(review.freeze(plan),review.catalogueRoot(catalog),roster,review.encode(run)));
        var partial = packet(Status.UNRESOLVED,Access.PARTIAL,false,false);
        var unapprovedLog=new QueryLog(review.freeze(plan),review.catalogueRoot(catalog),rows);
        var unapproved=new Packet(plan,catalog,unapprovedLog,new Dossier(review.logRoot(unapprovedLog),partial.dossier().assessment(),List.of()));
        assertEquals(Decision.NOVELTY_UNRESOLVED,decide(unapproved,roster,sign(unapproved,a,Vote.SUPPORT_SCOPED_NOVELTY),
            sign(unapproved,b,Vote.SUPPORT_SCOPED_NOVELTY)).outcome().decision());
        var wrong = new Translation(source.name(),"v2",first.family(),first.expression(),translated,"reviewed",hash("9"));
        var wrongPlan = new Plan(subject,plan.equation(),plan.assumptions(),plan.formationEvidenceRoot(),plan.exposureLedgerRoot(),
            plan.formationAccess(),plan.frozenAt(),List.of(wrong));
        var wrongLog = new QueryLog(review.freeze(wrongPlan),review.catalogueRoot(catalog),rows);
        assertThrows(IllegalArgumentException.class,()->review.packetRoot(new Packet(wrongPlan,catalog,wrongLog,
            new Dossier(review.logRoot(wrongLog),base.dossier().assessment(),List.of()))));
    }

    @Test void unrelatedActualQueriesCannotQualifyEvenIfAllGeneratedFormsAreListed() {
        var p = packet(Status.NO_PRIOR_ART_FOUND_WITHIN_SCOPE, Access.SEARCHED, false, false);
        var rows = p.log().entries().stream().map(q -> new Query(q.sourceIndex(),q.family(),q.expression(),
            "same unrelated query",q.executedAt(),q.resultCount(),q.hitIds())).toList();
        var log = new QueryLog(p.log().planRoot(),p.log().catalogueRoot(),rows);
        var changed = new Packet(plan,catalog,log,new Dossier(review.logRoot(log),p.dossier().assessment(),List.of()));
        assertThrows(IllegalArgumentException.class, () -> review.packetRoot(changed));
    }

    @Test void droppedOrSubstitutedHitsAndPreFreezeLookupsFail() {
        var p=packet(Status.NO_PRIOR_ART_FOUND_WITHIN_SCOPE,Access.SEARCHED,false,false);
        var first=p.log().entries().getFirst();
        var badRows=new ArrayList<>(p.log().entries());
        for(var wrong:List.of(new Query(0,first.family(),first.expression(),first.submittedText(),first.executedAt(),1,List.of("omitted hit")),
                new Query(0,first.family(),first.expression(),first.submittedText(),"2026-10-31T23:59:59Z",0,List.of()))) {
            badRows.set(0,wrong);var log=new QueryLog(p.log().planRoot(),p.log().catalogueRoot(),badRows);
            var badPacket=new Packet(plan,catalog,log,new Dossier(review.logRoot(log),p.dossier().assessment(),p.dossier().inspected()));
            assertThrows(IllegalArgumentException.class,()->review.packetRoot(badPacket));
        }
        var partialLog=new QueryLog(p.log().planRoot(),p.log().catalogueRoot(),p.log().entries().subList(1,3));
        assertThrows(IllegalArgumentException.class,()->review.packetRoot(new Packet(plan,catalog,partialLog,
            new Dossier(review.logRoot(partialLog),p.dossier().assessment(),p.dossier().inspected()))));
    }
}
