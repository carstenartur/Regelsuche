package de.regelsuche.literature;

import static de.regelsuche.literature.ExternalNoveltyReview.bad;
import de.regelsuche.literature.CandidateLiteratureAssessments.Access;
import de.regelsuche.literature.ExternalNoveltyReview.Packet;
import de.regelsuche.literature.ExternalNoveltyReview.Query;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Checks the complete sequence of observed hits against the existing private assessment. */
final class NoveltyCoverage {
    private NoveltyCoverage() { }
    static void check(ExternalNoveltyReview checker,Packet p) {
        var a=p.dossier().assessment();
        if(!checker.freeze(p.plan()).equals(p.log().planRoot())||!checker.catalogueRoot(p.catalogue()).equals(p.log().catalogueRoot())
                ||!checker.logRoot(p.log()).equals(p.dossier().logRoot())||!p.plan().subject().equals(a.subject()))
            throw bad("cross-bound review chain");
        if(!CandidateLiteratureAssessments.COVERAGE_SCHEMA.equals(a.schema())||p.catalogue().sources().size()!=a.scope().size())
            throw bad("invalid subject-bound source scope");
        checkInspectedMatches(p);
        var forms=NoveltyQueryForms.forEquation(p.plan().equation(), p.plan().assumptions());
        var logged=new HashSet<String>();
        for(var q:p.log().entries()) {
            if(q.sourceIndex()>=a.scope().size()||!q.expression().equals(forms.get(q.family()))) throw bad("unknown query form or source");
            logged.add(q.expression());
        }
        if(!Set.copyOf(a.queries()).equals(logged)) throw bad("assessment queries do not match the log");
        var catalogue = new HashSet<SourceVersion>();
        p.catalogue().sources().forEach(source -> catalogue.add(new SourceVersion(source.name(),source.version())));
        var translations = new HashMap<SourceForm,String>();
        for (var approved : p.plan().approvedTranslations()) {
            var source = new SourceVersion(approved.source(),approved.version());
            if (!catalogue.contains(source)) throw bad("approved translation uses a different catalogue source or revision");
            translations.put(new SourceForm(source,approved.family()),approved.approvedQuery());
        }
        for(int i=0;i<a.scope().size();i++) checkSource(p,i,forms.keySet(),translations);
    }
    private static void checkInspectedMatches(Packet packet) {
        var matches = packet.dossier().assessment().matches();
        var inspected = packet.dossier().inspected();
        if (matches.size() != inspected.size()) throw bad("each reviewed match requires inspected evidence");
        var seen = new HashSet<ExternalNoveltyReview.InspectedHit>();
        for (int index = 0; index < matches.size(); index++) {
            var match = matches.get(index);
            var hit = inspected.get(index);
            if (hit.sourceIndex() != match.scopeIndex() || !hit.identifier().equals(match.source().identifier())
                    || hit.relation().assessmentRelation != match.relation() || !seen.add(hit)) {
                throw bad("inspected evidence differs from its dossier match");
            }
        }
    }

    private record SourceVersion(String name,String version) {}
    private record SourceForm(SourceVersion source,String family) {}

    private static void checkSource(Packet p,int i,Set<String> families,Map<SourceForm,String> translations) {
        var src=p.catalogue().sources().get(i);
        var a=p.dossier().assessment();
        var scope=a.scope().get(i);
        if(!src.name().equals(scope.source())||!src.version().equals(scope.versionOrDate())) throw bad("substituted source");
        var queried=new HashSet<String>();
        var hits=new HashSet<String>();
        var frozen=Instant.parse(p.plan().frozenAt());
        var reviewed=LocalDate.parse(a.reviewedOn());
        var catalogued = LocalDate.parse(src.cataloguedOn());
        for(Query q:p.log().entries()) {
            if(q.sourceIndex()!=i) continue;
            var when=Instant.parse(q.executedAt());
            var date = when.atZone(ZoneOffset.UTC).toLocalDate();
            if(when.isBefore(frozen)||date.isBefore(catalogued)||date.isAfter(reviewed)||!queried.add(q.family()))
                throw bad("invalid query chronology or duplicate family");
            if (scope.coverage().status() == Access.SEARCHED) {
                var sourceForm = new SourceForm(new SourceVersion(src.name(),src.version()),q.family());
                if (!q.submittedText().equals(q.expression()) && !q.submittedText().equals(translations.get(sourceForm)))
                    throw bad("unapproved source translation requires partial coverage");
                if (q.resultCount() != q.hitIds().size())
                    throw bad("uninspected search results require partial coverage");
            }
            hits.addAll(q.hitIds());
        }
        if(scope.coverage().status()==Access.INACCESSIBLE&&!queried.isEmpty()) throw bad("searched inaccessible source");
        if(scope.coverage().status()==Access.SEARCHED&&!queried.equals(families)) throw bad("incomplete search family coverage");
        var explained=new HashSet<String>();
        a.matches().stream().filter(m->m.scopeIndex()==i).forEach(m->explained.add(m.source().identifier()));
        scope.coverage().unresolvedHits().forEach(h->explained.add(h.identifier()));
        if(!hits.equals(explained)) throw bad("unaccounted or unobserved literature hit");
    }
}
