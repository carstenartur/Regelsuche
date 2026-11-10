package de.regelsuche.evolution;
import java.util.*;
import de.regelsuche.parse.ExpressionFormatter;
public class Task3ProviderProbe {
 static CheckedSchemaTaskSolver.Task task(String s,int n){return MixedTaskKnowledgeWorkload.task(s,n);}
 static String nested(String a,int d,String b){if(d==0)return a;var s=nested(a,d-1,b);return "(("+s+"+"+b+d+")*("+s+"-"+b+d+")+"+b+d+"*"+b+d+")";}
 public static void main(String[] args){
 var initial=CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
 for(int d=1;d<=1;d++){
 int g=2*d+3;
 var u=initial.learnBatch(List.of(task(nested("a",d,"b")+"+3",g),task("5*("+nested("c",d,"d")+")",g)),
 List.of(task(nested("p*q",d,"v"),g),task(nested("z",d,"w")+"+7",g)),1_000_000);
 System.out.println("DEPTH "+d+" decision="+u.reason()+" formation="+u.attempts());
 for(var v:u.validation()){
 System.out.println(ExpressionFormatter.format(v.task().source())+" profile="+v.profile());
 for(var x:List.of(v.baseline(),v.previous(),v.candidate(),v.automatic().orElseThrow())) System.out.println("  "+x.selectedStrategy()+" work="+x.totalWork()+" solved="+x.solved()+" score="+x.score()+" output="+x.solution().map(s->ExpressionFormatter.format(s.evidence().incumbent().expression())));
 }
 }
 }
}
