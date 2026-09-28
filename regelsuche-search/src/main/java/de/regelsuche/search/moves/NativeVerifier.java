package de.regelsuche.search.moves;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Explicit trusted mathematical checker, independent of the candidate channel.
 * Supplying an implementation has the same trust responsibility as the historical Problem.verifier.
 * An accepted public receipt or a provider identifier does not install a checker.
 */
@FunctionalInterface
public interface NativeVerifier {
    NativeVerification verify(TypedMoveSearch.State source,NativeSearchMove proposal,TypedMoveSearch.Context context);

    /** Register only final built-in implementations or privately recognized installed checker types. */
    static NativeVerifier registered(List<NativeMoveProvider> providers) {
        var verifiers=new LinkedHashMap<MoveProvider.Descriptor,NativeVerifier>();
        for(var provider:List.copyOf(providers)) {
            NativeVerifier verifier=null;
            if(provider instanceof NativeMoveSearch.Primitive primitive)verifier=primitive::verify;
            else if(provider instanceof NativeProgramMoveProvider program)verifier=program::verify;
            else for(var installed:ServiceLoader.load(NativeVerifierProvider.class,NativeVerifier.class.getClassLoader())) {
                var recognized=Objects.requireNonNull(installed.recognize(provider));
                if(recognized.isPresent()) {
                    if(verifier!=null)throw new IllegalArgumentException("ambiguous installed native verifier");
                    verifier=recognized.orElseThrow();
                }
            }
            if(verifier==null)throw new IllegalArgumentException("unsupported native provider registration");
            if(verifiers.putIfAbsent(provider.descriptor(),verifier)!=null)throw new IllegalArgumentException("duplicate native provider descriptor");
        }
        return (source,proposal,context)->{
            var verifier=verifiers.get(proposal.descriptor());
            return verifier==null?new NativeVerification(false,1,null,null,"UNREGISTERED_NATIVE_PROVIDER"):
                verifier.verify(source,proposal,context);
        };
    }
}
