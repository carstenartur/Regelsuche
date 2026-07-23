#!/usr/bin/env python3
"""Independently verify the complete candidate-independent #383 execution index."""
from __future__ import annotations
import argparse, copy, hashlib, json, shutil, sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Callable
import jsonschema

SCHEMA='regelsuche.candidate-independent-benchmark-execution/v2'
CHALLENGES=['finite-difference-recurrences','rational-assumption-rewrites','reusable-search-macros']
EXPECTED_TOTALS={'configuredCampaigns':12,'executedCampaigns':12,'configuredCaseSlots':72,'executedCaseSlots':72,'successfulCaseSlots':52,'noResultCaseSlots':20,'detailedEvaluationRows':120,'correctnessRegressions':0}
class VerificationError(RuntimeError): pass
def fail(m): raise VerificationError(m)
def hook(pairs):
 r={}
 for k,v in pairs:
  if k in r: fail(f'duplicate JSON field {k!r}')
  r[k]=v
 return r
def load(p):
 if not p.is_file() or p.is_symlink(): fail(f'invalid file: {p}')
 try:v=json.loads(p.read_text(encoding='utf-8'),object_pairs_hook=hook)
 except Exception as e: fail(f'cannot read {p}: {e}')
 if not isinstance(v,dict): fail(f'{p} is not an object')
 return v
def canon(v): return json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
def sh(v): return 'sha256:'+hashlib.sha256(canon(v)).hexdigest()
def exact(p): return 'sha256:'+hashlib.sha256(p.read_bytes()).hexdigest()
def content(v,ctx):
 retained=v.get('contentHash'); material=dict(v); material.pop('contentHash',None); expected=sh(material)
 if retained!=expected: fail(f'{ctx} contentHash mismatch')
def schema_validate(v,s): jsonschema.Draft202012Validator(s).validate(v)
def count_sequence(v):
 slots=[e for c in v.get('campaigns',[]) for e in c.get('caseEvaluations',[])]
 return {'executedCampaigns':len(v.get('campaigns',[])),'caseSlots':len(slots),'successfulCaseSlots':sum(e.get('confirmedByAtLeastOneForm') is True for e in slots),'noResultCaseSlots':sum(e.get('confirmedByAtLeastOneForm') is not True for e in slots),'detailedEvaluationRows':len(slots),'candidateFormRows':sum(len(e.get('formResults',[])) for e in slots),'correctnessRegressions':0}
def count_tasks(v,field,success):
 slots=ok=nores=rows=reg=0
 for c in v.get('campaigns',[]):
  by=defaultdict(list)
  for e in c.get(field,[]): by[e.get('caseId')].append(e); rows+=1; reg+=int(e.get('correctnessRegression') is True)
  for vals in by.values():
   slots+=1; outcomes=[e.get('outcome') for e in vals]
   if all(o in success for o in outcomes): ok+=1
   elif all(o=='NO_RESULT' for o in outcomes): nores+=1
   else: fail(f'mixed unresolved case outcome: {outcomes}')
 return {'executedCampaigns':len(v.get('campaigns',[])),'caseSlots':slots,'successfulCaseSlots':ok,'noResultCaseSlots':nores,'detailedEvaluationRows':rows,'correctnessRegressions':reg}
def expected_summaries(seq,rat,mac):
 result={
  'finite-difference-recurrences':count_sequence(seq),
  'rational-assumption-rewrites':count_tasks(rat,'taskEvaluations',{'REACHED_AND_CONFIRMED'}),
  'reusable-search-macros':count_tasks(mac,'pairedEvaluations',{'IMPROVED','REACHABILITY_GAIN','NO_IMPROVEMENT'})}
 for v in result.values():
  if v['executedCampaigns']!=4 or v['caseSlots']!=24 or v['correctnessRegressions']!=0: fail(f'challenge coverage mismatch: {v}')
 return result
def validate_run(run,foundation,seq,rat,mac):
 content(run,'v2 execution')
 if run.get('schema')!=SCHEMA: fail('wrong v2 schema')
 if run.get('publicationAuthorized') is not False: fail('publication overclaim')
 if run.get('totals')!=EXPECTED_TOTALS: fail(f'total mismatch: {run.get("totals")}')
 f=run.get('foundation')
 if not isinstance(f,dict): fail('missing foundation binding')
 if f.get('runContentHash')!=foundation.get('contentHash'): fail('foundation root mismatch')
 if f.get('caseEvaluationSetContentHash')!=foundation.get('artifacts',{}).get('caseEvaluationSet',{}).get('contentHash'): fail('foundation case-set root mismatch')
 summaries=run.get('challengeExecutions')
 if not isinstance(summaries,list) or [x.get('challengeId') for x in summaries]!=CHALLENGES: fail('challenge order or membership changed')
 actual=expected_summaries(seq,rat,mac); roots={'finite-difference-recurrences':seq,'rational-assumption-rewrites':rat,'reusable-search-macros':mac}
 for summary in summaries:
  cid=summary['challengeId']; expected=actual[cid]
  for k,v in expected.items():
   if summary.get(k)!=v: fail(f'{cid} summary field {k} mismatch: {summary.get(k)} != {v}')
  if summary.get('contentHash')!=roots[cid].get('contentHash'): fail(f'{cid} root mismatch')
 totals={'configuredCampaigns':12,'executedCampaigns':sum(v['executedCampaigns'] for v in actual.values()),'configuredCaseSlots':72,'executedCaseSlots':sum(v['caseSlots'] for v in actual.values()),'successfulCaseSlots':sum(v['successfulCaseSlots'] for v in actual.values()),'noResultCaseSlots':sum(v['noResultCaseSlots'] for v in actual.values()),'detailedEvaluationRows':sum(v['detailedEvaluationRows'] for v in actual.values()),'correctnessRegressions':sum(v['correctnessRegressions'] for v in actual.values())}
 if totals!=EXPECTED_TOTALS: fail(f'recomputed totals changed: {totals}')
def expect(action:Callable[[],None],label):
 try: action()
 except (VerificationError,jsonschema.ValidationError) as e:return {'mutationId':label,'rejected':True,'detail':str(e)}
 fail(f'mutation accepted: {label}')
def main():
 ap=argparse.ArgumentParser(); ap.add_argument('--repository-root',type=Path,required=True); ap.add_argument('--first',type=Path,required=True); ap.add_argument('--second',type=Path,required=True); ap.add_argument('--foundation-first',type=Path,required=True); ap.add_argument('--foundation-second',type=Path,required=True); ap.add_argument('--sequence-first',type=Path,required=True); ap.add_argument('--sequence-second',type=Path,required=True); ap.add_argument('--rational-first',type=Path,required=True); ap.add_argument('--rational-second',type=Path,required=True); ap.add_argument('--macro-first',type=Path,required=True); ap.add_argument('--macro-second',type=Path,required=True); ap.add_argument('--report-directory',type=Path,required=True); a=ap.parse_args()
 root=a.repository_root.resolve(); schema=load(root/'docs/schemas/regelsuche-candidate-independent-benchmark-execution-v2.schema.json'); seq_schema=load(root/'docs/schemas/regelsuche-candidate-independent-finite-sequence-form-aggregate-v1.schema.json'); rat_schema=load(root/'docs/schemas/regelsuche-candidate-independent-rational-assumption-adapter-run-v1.schema.json'); mac_schema=load(root/'docs/schemas/regelsuche-candidate-independent-reusable-macro-batch-v1.schema.json')
 paths=[(a.first,a.second,schema),(a.sequence_first,a.sequence_second,seq_schema),(a.rational_first,a.rational_second,rat_schema),(a.macro_first,a.macro_second,mac_schema)]
 values=[]
 for first,second,s in paths:
  x,y=load(first),load(second); schema_validate(x,s); schema_validate(y,s)
  if first.read_bytes()!=second.read_bytes(): fail(f'non-identical run pair: {first} {second}')
  values.append(x)
 foundation1=load(a.foundation_first/'benchmark-run.json'); foundation2=load(a.foundation_second/'benchmark-run.json')
 if (a.foundation_first/'benchmark-run.json').read_bytes()!=(a.foundation_second/'benchmark-run.json').read_bytes(): fail('foundation run pair differs')
 run,seq,rat,mac=values; validate_run(run,foundation1,seq,rat,mac)
 mutations=[]
 m=copy.deepcopy(run);m['publicationAuthorized']=True;mutations.append(expect(lambda:validate_run(m,foundation1,seq,rat,mac),'publication-overclaim'))
 m=copy.deepcopy(run);m['totals']['executedCampaigns']=11;mutations.append(expect(lambda:validate_run(m,foundation1,seq,rat,mac),'missing-campaign'))
 m=copy.deepcopy(run);m['challengeExecutions'].pop();mutations.append(expect(lambda:validate_run(m,foundation1,seq,rat,mac),'missing-challenge'))
 m=copy.deepcopy(run);m['foundation']['runContentHash']='sha256:'+'0'*64;mutations.append(expect(lambda:validate_run(m,foundation1,seq,rat,mac),'foundation-rebinding'))
 m=copy.deepcopy(run);m['unexpected']='x';mutations.append(expect(lambda:schema_validate(m,schema),'unknown-field'))
 report={'schema':'regelsuche.candidate-independent-benchmark-execution-verification/v2','benchmarkId':run['benchmarkId'],'executionContentHash':run['contentHash'],'firstExactHash':exact(a.first),'secondExactHash':exact(a.second),'byteIdentical':True,'executedCampaigns':12,'executedCaseSlots':72,'successfulCaseSlots':52,'noResultCaseSlots':20,'detailedEvaluationRows':120,'correctnessRegressions':0,'mutationTests':mutations,'externalNoveltyStatus':'NOT_EVALUATED','expertInterestingnessStatus':'NOT_EVALUATED','publicationAuthorized':False};report['contentHash']=sh(report)
 a.report_directory.mkdir(parents=True,exist_ok=True);out=a.report_directory/'verification.json';out.write_text(json.dumps(report,ensure_ascii=False,indent=2,sort_keys=True)+'\n',encoding='utf-8');shutil.copy2(a.first,a.report_directory/'first-run.json');shutil.copy2(a.second,a.report_directory/'second-run.json');print(f'candidateIndependentBenchmarkExecutionVerification={out}');print(f'contentHash={report["contentHash"]}')
if __name__=='__main__':
 try: main()
 except (VerificationError,jsonschema.ValidationError) as e: print(f'verification failed: {e}',file=sys.stderr);raise SystemExit(1)
