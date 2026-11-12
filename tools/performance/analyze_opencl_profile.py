#!/usr/bin/env python3
"""Summarize profiling overhead and export RenderThread/EGL evidence.

Clock alignment is permitted only with valid device-to-host anchors AND Perfetto
BOOTTIME snapshots. Temporal overlap is reported, never labelled proof of cause.
"""
import argparse
import csv
import io
import json
from pathlib import Path
import statistics
import subprocess

QUERIES = {
    'clocks': 'SELECT ts,clock_id,clock_name,clock_value,snapshot_id FROM clock_snapshot',
    'trace-errors': "SELECT name,value,severity FROM stats WHERE value>0 AND severity IN ('error','data_loss')",
    'render-slices': """SELECT s.id,s.ts,s.dur,s.name,t.tid,t.name AS thread_name,p.pid
FROM slice s JOIN thread_track tt ON tt.id=s.track_id JOIN thread t ON t.utid=tt.utid
JOIN process p ON p.upid=t.upid WHERE p.pid={pid} AND (t.name='RenderThread' OR t.tid=p.pid)
AND s.dur>=0 ORDER BY s.ts""",
    'thread-states': """SELECT st.ts,st.dur,st.state,st.io_wait,st.blocked_function,t.tid,t.name AS thread_name,p.pid
FROM thread_state st JOIN thread t ON t.utid=st.utid JOIN process p ON p.upid=t.upid
WHERE p.pid={pid} AND (t.name='RenderThread' OR t.tid=p.pid) AND st.dur>=0 ORDER BY st.ts""",
    'round-markers': "SELECT ts,dur,name FROM slice WHERE name LIKE 'LLM_PROFILE_%' ORDER BY ts",
}


def query(processor, trace, sql):
    result = subprocess.run([processor, 'query', str(trace), sql],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120, check=True)
    text = result.stdout.decode()
    return text, list(csv.DictReader(io.StringIO(text)))


def percentile(values, fraction):
    return sorted(values)[min(int((len(values)-1)*fraction), len(values)-1)] if values else None


def distribution(values):
    return dict(count=len(values), median=statistics.median(values) if values else None,
                p95=percentile(values,.95), maximum=max(values) if values else None)


def align_event(event, anchors):
    """Return bounded host projection, or None; do not assume clocks match."""
    for a in anchors:
        if a['queue'] != event['queue'] or a['status'] != 0 or a['deviceNs'] <= 0:
            continue
        before, after = a['hostBeforeBootNs'], a['hostAfterBootNs']
        if after < before:
            continue
        # The device timer/marker time occurred within this host call bracket.
        offset_lo, offset_hi = before-a['deviceNs'], after-a['deviceNs']
        queued, submitted, start, end = event['gpuTimesNs']
        if end > a['deviceNs'] or start + offset_hi < event['hostEnqueueBeginBootNs']:
            continue
        return dict(startBootNs=(start+(offset_lo+offset_hi)//2),
                    endBootNs=(end+(offset_lo+offset_hi)//2),
                    uncertaintyNs=(offset_hi-offset_lo)//2, method=a['method'])
    return None


def summarize(report):
    rounds=[r for r in report['rounds'] if not r['warmup']]
    events=[e for r in rounds for e in r['openCl']['events']]
    for e in events:
        if e['profilingStatus'] != [0,0,0,0] or e['executionStatus'] != 0:
            raise ValueError('Invalid profiling events cannot be interpreted')
    def duration(i,j):
        return [(e['gpuTimesNs'][j]-e['gpuTimesNs'][i])/1e6 for e in events]
    return dict(pid=report['pid'], priority=report['requestedLowPriority'], profiling=report['profiling'],
                measuredRounds=len(rounds), generationMs=distribution([r['generationMs'] for r in rounds]),
                firstTextMs=distribution([r['firstTextMs'] for r in rounds]),
                uiCallbackGapMs=distribution([g for r in rounds for g in r['uiFrameGapsMs']]),
                collectorMs=distribution([r['eventCollectionMs'] for r in rounds]),
                queuedToSubmitMs=distribution(duration(0,1)), submitToStartMs=distribution(duration(1,2)),
                deviceExecutionIntervalMs=distribution(duration(2,3)),
                outputs=sorted({r['output'] for r in rounds}))


def analyze_trace(processor, trace, report, output):
    result={}
    for name, sql in QUERIES.items():
        text, rows=query(processor, trace, sql.format(pid=report['pid']))
        (output/f'{report["label"]}-{name}.csv').write_text(text)
        result[name]=rows
    # Perfetto canonical timestamps need not equal raw Android BOOTTIME.
    offsets=[int(r['ts'])-int(r['clock_value']) for r in result['clocks']
             if r['clock_id']=='6' or r['clock_name']=='BOOTTIME']
    render=[r for r in result['render-slices'] if r['thread_name']=='RenderThread']
    egl=[r for r in render if 'egl' in r['name'].lower() and 'swap' in r['name'].lower()]
    marker_names={r['name'] for r in result['round-markers']}
    expected='LLM_PROFILE_'+report['label']
    health=dict(renderThreadSlices=len(render), eglSwapSlices=len(egl),
                roundMarkerPresent=expected in marker_names,
                traceErrorCount=len(result['trace-errors']), bootClockAvailable=bool(offsets))
    if not offsets:
        health['alignment']='unavailable: no BOOTTIME snapshot; no clock conversion assumed'
        return health
    # A large offset disagreement invalidates simple constant-offset conversion.
    if max(offsets)-min(offsets)>100_000:
        health['alignment']='unavailable: trace clock offset varies by more than 100 us'
        return health
    trace_offset=int(statistics.median(offsets))
    aligned=[]
    for r in report['rounds']:
        if r['warmup']:continue
        for e in r['openCl']['events']:
            projection=align_event(e,r['openCl']['clockAnchors'])
            if projection:
                aligned.append(dict(round=r['round'], kernel=e['kernel'], queue=e['queue'],
                                    startTraceNs=projection['startBootNs']+trace_offset,
                                    endTraceNs=projection['endBootNs']+trace_offset,
                                    uncertaintyNs=projection['uncertaintyNs'],method=projection['method']))
    with (output/f'{report["label"]}-aligned-kernels.csv').open('w') as f:
        fields=['round','kernel','queue','startTraceNs','endTraceNs','uncertaintyNs','method']
        writer=csv.DictWriter(f,fieldnames=fields);writer.writeheader();writer.writerows(aligned)
    measured=[(r['startBootNs']+trace_offset,r['endBootNs']+trace_offset)
              for r in report['rounds'] if not r['warmup']]
    relevant=[s for s in egl if any(int(s['ts'])<end and int(s['ts'])+int(s['dur'])>start for start,end in measured)]
    health.update(alignment='bounded device-host anchors plus Perfetto BOOTTIME conversion',
                  alignedKernelCount=len(aligned), canonicalMinusBootNs=trace_offset,
                  measuredEglSwapSliceMs=distribution([int(s['dur'])/1e6 for s in relevant]))
    return health


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input',type=Path)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--trace-processor',required=True)
    p.add_argument('--self-check',action='store_true',help='Check SQL schema using an empty trace; no device result')
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    if a.self_check:
        empty=a.output/'empty.json';empty.write_text('{"traceEvents":[]}')
        for name,sql in QUERIES.items():
            query(a.trace_processor,empty,sql.format(pid=0))
        print('SQL schema checks passed; no device measurements were analyzed')
        return
    if not a.input:p.error('--input is required unless --self-check is used')
    manifest=json.loads((a.input/'manifest.json').read_text())
    if manifest['state']!='captured-awaiting-trace-analysis' or len(manifest['completed'])!=len(manifest['conditions']):
        raise ValueError('Capture is incomplete; do not produce comparison results')
    summaries=[]
    for entry in manifest['completed']:
        label=entry['label']; report=json.loads((a.input/f'{label}.json').read_text())
        summary=summarize(report)
        summary['label']=label
        summary['trace']=analyze_trace(a.trace_processor,a.input/f'{label}.pftrace',report,a.output)
        summaries.append(summary)
    overhead={}
    for priority in ('false','true'):
        for mode in (False,True):
            subset=[x for x in summaries if x['priority']==priority and x['profiling']==mode]
            overhead[(priority,str(mode))]=statistics.median(x['generationMs']['median'] for x in subset)
    comparisons=[]
    for priority in ('false','true'):
        off,on=overhead[(priority,'False')],overhead[(priority,'True')]
        comparisons.append(dict(lowPriority=priority, profilingOffMedianOfRunMediansMs=off,
                                profilingOnMedianOfRunMediansMs=on, generationOverheadPercent=(on/off-1)*100))
    result=dict(state='analyzed-not-causal-proof',runs=summaries,profilingOverhead=comparisons,
                caveat='Both controls include the forwarding layer and identical trace configuration. '
                       'Overlapping GPU work and EGL wall-time do not establish the cause of a wait.')
    (a.output/'summary.json').write_text(json.dumps(result,indent=2,ensure_ascii=False))
    print(json.dumps(comparisons,indent=2))


if __name__=='__main__':main()
