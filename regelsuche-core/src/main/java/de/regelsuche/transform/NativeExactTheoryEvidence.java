package de.regelsuche.transform;

import de.regelsuche.ast.Expr;
import java.util.Objects;
import java.util.ServiceLoader;

/** Privately issued installed-checker capability; its public observation cannot issue another capability. */
public final class NativeExactTheoryEvidence {
    public record Binding(Expr source,Expr target,String theoryStepId,long canonicalWorkUnits,Object observation) {
        public Binding {
            Objects.requireNonNull(source);Objects.requireNonNull(target);Objects.requireNonNull(observation);
            if(source.equals(target) || theoryStepId==null || theoryStepId.isBlank() || canonicalWorkUnits<1)
                throw new IllegalArgumentException("invalid native exact binding");
        }
    }
    private final Binding binding;
    private final Object issued;
    private final ExactTheoryEvidenceProvider provider;
    private NativeExactTheoryEvidence(Binding binding,Object issued,ExactTheoryEvidenceProvider provider){
        this.binding=binding;this.issued=issued;this.provider=provider;
    }
    public static NativeExactTheoryEvidence fromVerified(Object issued) {
        Objects.requireNonNull(issued);NativeExactTheoryEvidence accepted=null;
        for(var provider:ServiceLoader.load(ExactTheoryEvidenceProvider.class,NativeExactTheoryEvidence.class.getClassLoader())) {
            var binding=Objects.requireNonNull(provider.bindNative(issued));
            if(binding.isPresent()) {
                if(accepted!=null)throw new IllegalArgumentException("ambiguous installed native evidence providers");
                accepted=new NativeExactTheoryEvidence(binding.orElseThrow(),issued,provider);
            }
        }
        if(accepted==null)throw new IllegalArgumentException("no installed verifier recognizes native evidence capability");
        return accepted;
    }
    public Binding binding(){return binding;}
    /** Explicit persistence/export boundary, never native generation or verification. */
    public ExactTheoryEvidence exportLegacy(){return ExactTheoryEvidence.fromVerified(provider.exportNative(issued));}
    @Override public boolean equals(Object other){return other instanceof NativeExactTheoryEvidence evidence && binding.equals(evidence.binding);}
    @Override public int hashCode(){return binding.hashCode();}
}
