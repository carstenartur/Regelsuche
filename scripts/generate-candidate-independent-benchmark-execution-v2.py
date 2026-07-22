#!/usr/bin/env python3
"""Generate a deterministic v2 index over all executed #383 challenge adapters."""
from __future__ import annotations
import argparse, hashlib, json
from collections import defaultdict
from pathlib import Path
from typing import Any

SCHEMA='regelsuche.candidate-independent-benchmark-execution/v2'
BENCHMARK_ID='regelsuche-candidate-independent-autonomous-discovery-2026-07/v1'
CLAIM_POLICY='EXECUTED_BENCHMARK_DOES_NOT_AUTHORIZE_NOVELTY_PROOF_OR_PUBLICATION'

def fail(msg): raise SystemExit('benchmark execution generation failed: '+msg)
def load(p:Path):
    try: v=json.loads(p.read_text(encoding='utf-8'))
    except Exception as e: fail(f'cannot read {p}: {e}')
    if not isinstance(v,dict): fail(f'{p} is not an object')
    return v
def canon(v): return json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
def sh(v): return 'sha256:'+hashlib.sha256(canon(v)).hexdigest()
def exact(p): return 'sha256:'+hashlib.sha256(p.read_bytes()).hexdigest()
def hashed(v):
    r=dict(v); r['contentHash']=sh(v); return r

def sequence_summary(run):
    if run.get('schema')!='regelsuche.candidate-independent-finite-sequence-form-aggregate/v1': fail('wrong sequence schema')
    camps=run.get('campaigns',[]); slots=[]
    for c in camps: slots.extend(c.get('caseEvaluations',[]))
    success=sum(1 for e in slots if e.get('confirmedByAtLeastOneForm') is True)
    conflicts=sum(1 for e in slots if e.get('modelOutcomeConflict') is True)
    return {'challengeId':'finite-difference-recurrences','schema':run['schema'],'contentHash':run['contentHash'],'executedCampaigns':len(camps),'caseSlots':len(slots),'successfulCaseSlots':success,'noResultCaseSlots':len(slots)-success,'detailedEvaluationRows':len(slots),'candidateFormRows':sum(len(e.get('formResults',[])) for e in slots),'modelConflictCaseSlots':conflicts,'correctnessRegressions':0,'challengeStatus':run.get('challengeStatus')}

def grouped_task_summary(run, campaign_field, success_outcomes, improvement_outcomes=()):
    camps=run.get('campaigns',[]); case_slots=success=nores=mixed=improved=reg=rows=0
    for c in camps:
        by=defaultdict(list)
        for e in c.get(campaign_field,[]):
            by[e.get('caseId')].append(e); rows+=1
            reg += int(e.get('correctnessRegression') is True)
        for vals in by.values():
            case_slots += 1
            outcomes=[e.get('outcome') for e in vals]
            if all(o in success_outcomes for o in outcomes):
                success += 1
                if improvement_outcomes and all(o in improvement_outcomes for o in outcomes): improved += 1
            elif all(o=='NO_RESULT' for o in outcomes): nores += 1
            else: mixed += 1
    return camps,case_slots,success,nores,mixed,improved,reg,rows

def rational_summary(run):
    if run.get('schema')!='regelsuche.candidate-independent-rational-assumption-adapter-run/v1': fail('wrong rational schema')
    camps,slots,success,nores,mixed,_,reg,rows=grouped_task_summary(run,'taskEvaluations',{'REACHED_AND_CONFIRMED'})
    return {'challengeId':'rational-assumption-rewrites','schema':run['schema'],'contentHash':run['contentHash'],'executedCampaigns':len(camps),'caseSlots':slots,'successfulCaseSlots':success,'noResultCaseSlots':nores,'mixedCaseSlots':mixed,'detailedEvaluationRows':rows,'reachedAndConfirmedTaskEvaluations':run.get('reachedAndConfirmedTaskEvaluations'),'noResultTaskEvaluations':run.get('noResultTaskEvaluations'),'correctnessRegressions':reg,'challengeStatus':run.get('adapterStatus')}

def macro_summary(run):
    if run.get('schema')!='regelsuche.candidate-independent-reusable-macro-batch/v1': fail('wrong macro schema')
    success_outcomes={'IMPROVED','REACHABILITY_GAIN','NO_IMPROVEMENT'}
    camps,slots,success,nores,mixed,improved,reg,rows=grouped_task_summary(run,'pairedEvaluations',success_outcomes,{'IMPROVED','REACHABILITY_GAIN'})
    return {'challengeId':'reusable-search-macros','schema':run['schema'],'contentHash':run['contentHash'],'executedCampaigns':len(camps),'caseSlots':slots,'successfulCaseSlots':success,'noResultCaseSlots':nores,'mixedCaseSlots':mixed,'improvedCaseSlots':improved,'detailedEvaluationRows':rows,'aggregateOutcomeCounts':run.get('aggregateOutcomeCounts'),'correctnessRegressions':reg,'challengeStatus':run.get('adapterStatus')}

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--foundation',type=Path,required=True); ap.add_argument('--sequence',type=Path,required=True); ap.add_argument('--rational',type=Path,required=True); ap.add_argument('--macro',type=Path,required=True); ap.add_argument('--output',type=Path,required=True); ap.add_argument('--repository-revision',default='WORKTREE'); a=ap.parse_args()
    foundation_run=load(a.foundation/'benchmark-run.json'); foundation_batch=load(a.foundation/'campaign-batch.json'); foundation_report=load(a.foundation/'benchmark-report.json')
    seq=load(a.sequence); rat=load(a.rational); mac=load(a.macro)
    if foundation_run.get('schema')!='regelsuche.candidate-independent-benchmark-run/v1' or foundation_run.get('status')!='GENERATED_NOT_EVALUATED': fail('invalid v1 foundation run')
    if foundation_batch.get('configuredCampaigns')!=12 or foundation_batch.get('executedCampaigns')!=0: fail('invalid v1 campaign freeze')
    if foundation_report.get('configuredEvaluations')!=72 or foundation_report.get('executedEvaluations')!=0: fail('invalid v1 case freeze')
    summaries=[sequence_summary(seq),rational_summary(rat),macro_summary(mac)]
    summaries.sort(key=lambda x:x['challengeId'])
    totals={'configuredCampaigns':12,'executedCampaigns':sum(x['executedCampaigns'] for x in summaries),'configuredCaseSlots':72,'executedCaseSlots':sum(x['caseSlots'] for x in summaries),'successfulCaseSlots':sum(x['successfulCaseSlots'] for x in summaries),'noResultCaseSlots':sum(x['noResultCaseSlots'] for x in summaries),'detailedEvaluationRows':sum(x['detailedEvaluationRows'] for x in summaries),'correctnessRegressions':sum(x['correctnessRegressions'] for x in summaries)}
    expected={'configuredCampaigns':12,'executedCampaigns':12,'configuredCaseSlots':72,'executedCaseSlots':72,'successfulCaseSlots':52,'noResultCaseSlots':20,'detailedEvaluationRows':120,'correctnessRegressions':0}
    if totals!=expected: fail(f'aggregate changed: {totals}')
    value={'schema':SCHEMA,'benchmarkId':BENCHMARK_ID,'repositoryRevision':a.repository_revision,'foundation':{'schema':foundation_run['schema'],'runContentHash':foundation_run['contentHash'],'runExactHash':exact(a.foundation/'benchmark-run.json'),'campaignBatchContentHash':foundation_batch['contentHash'],'benchmarkReportContentHash':foundation_report['contentHash'],'caseEvaluationSetContentHash':foundation_run['artifacts']['caseEvaluationSet']['contentHash'],'configuredCampaigns':12,'configuredCaseSlots':72,'executionStatus':'FROZEN_NOT_EXECUTED'},'challengeExecutions':summaries,'totals':totals,'benchmarkStatus':'COMPLETE_FROZEN_CHALLENGE_EXECUTION','claimPolicy':CLAIM_POLICY,'formalProofStatus':'NOT_EVALUATED_AT_BENCHMARK_AGGREGATE','externalNoveltyStatus':'NOT_EVALUATED','expertInterestingnessStatus':'NOT_EVALUATED','publicationAuthorized':False}
    value=hashed(value); a.output.parent.mkdir(parents=True,exist_ok=True); a.output.write_text(json.dumps(value,ensure_ascii=False,indent=2,sort_keys=True)+'\n',encoding='utf-8'); print(f'candidateIndependentBenchmarkExecution={a.output}'); print(f'contentHash={value["contentHash"]}')
if __name__=='__main__': main()
