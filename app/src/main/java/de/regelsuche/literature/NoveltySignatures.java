package de.regelsuche.literature;

import static de.regelsuche.literature.ExternalNoveltyReview.bad;
import de.regelsuche.literature.ExternalNoveltyReview.Intake;
import de.regelsuche.literature.ExternalNoveltyReview.Reviewer;
import de.regelsuche.literature.ExternalNoveltyReview.Vote;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

final class NoveltySignatures {
    private NoveltySignatures() { }
    static List<Vote> verify(ExternalNoveltyReview checker,String packet,Intake intake,List<Reviewer> eligible) {
        var registry=new HashMap<String,Reviewer>();
        var keys=new HashSet<String>();
        for(var member:eligible) {
            var key=readKey(member.publicKey());
            if(registry.put(member.identityRoot(),member)!=null||!keys.add(Base64.getEncoder().encodeToString(key.getEncoded())))
                throw bad("duplicate eligible person or key");
        }
        var seen=new HashSet<String>();
        var votes=new ArrayList<Vote>();
        for(var review:intake.reviews()) {
            var claim=review.opinion();
            var member=registry.get(claim.reviewerRoot());
            if(member==null||!seen.add(member.identityRoot())) throw bad("ineligible or duplicate reviewer");
            if(!packet.equals(claim.packetRoot())||!claim.qualificationRoot().equals(member.qualificationRoot())
                    ||!claim.conflictClearanceRoot().equals(member.conflictClearanceRoot())) throw bad("wrong packet or attestation");
            verifySignature(readKey(member.publicKey()),checker.signingBytes(claim),review.signature()); votes.add(claim.vote());
        }
        return List.copyOf(votes);
    }
    private static PublicKey readKey(String encoded) {
        try {
            var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if(!Base64.getEncoder().encodeToString(key.getEncoded()).equals(encoded)) throw bad("non-canonical reviewer key");
            return key;
        } catch(GeneralSecurityException e) { throw new IllegalArgumentException("invalid reviewer key",e); }
    }
    private static void verifySignature(PublicKey key,byte[] payload,String signature) {
        try {
            var verifier=Signature.getInstance("Ed25519"); verifier.initVerify(key); verifier.update(payload);
            if(!verifier.verify(Base64.getDecoder().decode(signature))) throw bad("invalid reviewer signature");
        } catch(GeneralSecurityException e) { throw new IllegalArgumentException("invalid reviewer signature",e); }
    }
}
