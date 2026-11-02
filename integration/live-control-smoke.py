#!/usr/bin/env python3
"""Verify packaged SSE, conditional control and two browsers using only loopback DSPs."""
import argparse
import http.client
import importlib.util
import json
import math
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('live_package_fixture',HERE/'server-package-smoke.py')
package=importlib.util.module_from_spec(spec);sys.modules[spec.name]=package;spec.loader.exec_module(package)


class Events:
    def __init__(self,port,dsp_id=None):
        self.connection=http.client.HTTPConnection('127.0.0.1',port,timeout=8)
        query='' if dsp_id is None else '?dspId='+urllib.parse.quote(dsp_id,safe='')
        self.connection.request('GET','/api/v1/events'+query,headers={'Accept':'text/event-stream'})
        self.response=self.connection.getresponse()
        if self.response.status!=200:
            body=self.response.read().decode();self.connection.close()
            raise AssertionError('SSE status '+str(self.response.status)+': '+body)
        if not self.response.getheader('Content-Type','').startswith('text/event-stream'):
            self.connection.close();raise AssertionError('SSE content type is unavailable')
        self.socket=self.connection.sock;self.socket.settimeout(15)
        self.frames=[];self.condition=threading.Condition();self.closed=False;self.error=None
        self.worker=threading.Thread(target=self.read,daemon=True);self.worker.start()

    def read(self):
        event='message';data=[];cursor=None
        try:
            while not self.closed:
                line=self.response.readline()
                if not line:break
                line=line.decode().rstrip('\r\n')
                if not line:
                    if data:
                        frame={'event':event,'id':cursor,'data':json.loads('\n'.join(data)),'at':time.monotonic()}
                        with self.condition:
                            self.frames.append(frame);self.condition.notify_all()
                    event='message';data=[];cursor=None
                elif line.startswith('event:'):event=line[6:].lstrip()
                elif line.startswith('data:'):data.append(line[5:].lstrip())
                elif line.startswith('id:'):cursor=line[3:].lstrip()
        except Exception as error:
            if not self.closed:self.error=str(error)
        finally:
            with self.condition:self.condition.notify_all()

    def wait(self,event,predicate=lambda _:True,after=0,timeout=5):
        deadline=time.monotonic()+timeout
        with self.condition:
            while True:
                for frame in self.frames[after:]:
                    if frame['event']==event and predicate(frame['data']):return frame
                remaining=deadline-time.monotonic()
                if remaining<=0:raise AssertionError('Missing SSE '+event+'; stream error='+str(self.error))
                self.condition.wait(remaining)

    def close(self):
        if self.closed:return
        self.closed=True
        try:self.socket.shutdown(socket.SHUT_RDWR)
        except OSError:pass
        self.worker.join(2);self.response.close();self.connection.close()
        if self.worker.is_alive():raise AssertionError('Owned SSE reader did not stop')


class LiveCheck(package.PackageCheck):
    def exchange(self,path,body=None,headers=None,expected=200):
        request=urllib.request.Request('http://127.0.0.1:'+str(self.port)+'/api/v1'+path,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type':'application/json',**(headers or {})})
        try:response=urllib.request.urlopen(request,timeout=10)
        except urllib.error.HTTPError as error:response=error
        with response:
            payload=json.loads(response.read());etag=response.headers.get('ETag')
            self.result['http'].append({'path':path,'method':request.get_method(),'status':response.status,'etag':etag})
            if response.status!=expected:raise AssertionError(f'Expected HTTP {expected}, received {response.status}: {payload}')
            return payload,etag

    def stream(self,device=None):
        deadline=time.monotonic()+20
        while True:
            try:
                stream=Events(self.port,device);self.streams.append(stream);return stream
            except (OSError,AssertionError):
                if time.monotonic()>deadline:raise
                time.sleep(.2)

    def run(self):
        self.streams=[]
        self.unpacked=package.extract(Path(self.args.archive),self.state)
        self.install=self.state/'installed';self.runtime=self.state/'persistent-state'
        args=['install','--install-dir',self.install,'--state-dir',self.runtime]
        if self.args.allow_development_build:args.append('--allow-development-build')
        self.control(*args)
        self.result['release']=json.loads((self.install/'current/release.json').read_text())
        settings_file=self.runtime/'config/server.json';settings=json.loads(settings_file.read_text())
        self.port=package.free_port();settings.update(httpPort=self.port,webEnabled=True)
        settings_file.write_text(json.dumps(settings,indent=2)+'\n')
        with package.fixtures.FakeDsp(allow_parameter_writes=True) as peer:
            self.start()
            deadline=time.monotonic()+15
            while True:
                try:self.request('/app/');break
                except (AssertionError,OSError):
                    if time.monotonic()>deadline:raise
                    time.sleep(.2)
            listing=self.stream();listing.wait('devices',lambda data:data['dsps']==[])
            definition={'id':'live','host':'127.0.0.1','port':peer.port,'library':'fir408','autoConnect':False}
            self.request('/api/v1/devices','POST',definition,expected=201)
            listing.wait('devices',lambda data:any(d['id']=='live' for d in data['dsps']))
            first,second=self.stream('live'),self.stream('live')
            first.wait('state',lambda data:not data['connected']);second.wait('meters')
            if peer.connections:raise AssertionError('SSE registration initiated a DSP connection')
            self.request('/api/v1/dsps/live/connection/connect','POST',timeout=60)
            first.wait('state',lambda data:data['connected'] and data['settingsCache']['ready'])
            second.wait('state',lambda data:data['connected'] and data['settingsCache']['ready'])
            count=lambda command:sum(payload[6:8]==command for payload in peer.requests)
            before_reads=count('27');before_meters=count('40');started=time.monotonic();time.sleep(1.1)
            if count('27')!=before_reads or count('40')-before_meters>math.ceil((time.monotonic()-started)/.2)+3:
                raise AssertionError('Subscribers added physical reads or meter pollers')
            state,etag=self.exchange('/dsps/live/control-state')
            if etag!='"'+state['instanceId']+'.'+str(state['revision'])+'"':raise AssertionError('ETag does not bind control snapshot')
            request={'channelId':'out8','parameter':'gain','value':-12.3,'verify':False}
            headers={'If-Match':etag,'Idempotency-Key':'live-smoke-write-00000001','Prefer':'wait=0'}
            wires=count('34');start=time.monotonic()
            written,_=self.exchange('/dsps/live/parameters/write',request,headers)
            if written['status']!='ACKNOWLEDGED':raise AssertionError('ACK-only write was not acknowledged')
            def changed(data):return data['revision']>state['revision'] and any(c['id']=='out8' and abs(c['gainDb']+12.3)<.01 for c in data['channels'])
            a=first.wait('state',changed);b=second.wait('state',changed)
            self.result['pushFeedbackMs']=round(1000*(max(a['at'],b['at'])-start),3)
            if self.result['pushFeedbackMs']>2000:raise AssertionError('Live control push was delayed')
            replay,_=self.exchange('/dsps/live/parameters/write',request,headers)
            if replay!=written or count('34')!=wires+1:raise AssertionError('Replay transmitted a second command')
            conflict,_=self.exchange('/dsps/live/parameters/write',{**request,'value':-10},
                {**headers,'Idempotency-Key':'live-smoke-write-00000002'},expected=412)
            if conflict.get('transmitted') is not False or conflict.get('errorCode')!='revision_conflict':
                raise AssertionError('Stale browser did not get definite unsent conflict')
            if count('34')!=wires+1 or count('27')!=before_reads:raise AssertionError('Conflict or ACK added a wire write/readback')
            current,new_tag=self.exchange('/dsps/live/control-state')
            self.exchange('/dsps/live/parameters/write',request,{**headers,'If-Match':new_tag},expected=409)
            third=self.stream('live');third.wait('state',lambda data:data['revision']>=current['revision'])
            if count('27')!=before_reads:raise AssertionError('Stream reconnection read physical configuration')
            if self.args.browser_check:
                output=self.state/'browser-live.json'
                result=subprocess.run(['node',str(HERE/'browser/live-server.mjs'),'http://127.0.0.1:'+str(self.port),str(output),'live'],timeout=60)
                self.result['browser']=json.loads(output.read_text())
                if result.returncode or not self.result['browser']['ok']:raise AssertionError('Actual packaged browser checks failed')
                if count('34')!=wires+3:raise AssertionError('Two browser edits did not produce exactly two additional writes')
            self.request('/api/v1/devices/live','DELETE',expected=204)
            for stream in (first,second,third):stream.wait('removed',lambda data:data['instanceId']==state['instanceId'])
            self.request('/api/v1/devices','POST',definition,expected=201)
            replacement,_=self.exchange('/dsps/live/control-state')
            if replacement['instanceId']==state['instanceId']:raise AssertionError('Replacement reused runtime identity')
            self.exchange('/dsps/live/parameters/write',request,headers,expected=409)
            self.exchange('/dsps/live/parameters/write',request,{**headers,'Idempotency-Key':'live-smoke-write-00000003'},expected=412)
            if count('27')!=before_reads:raise AssertionError('Replacement triggered a device read')
            for stream in self.streams:stream.close()
            self.streams=[];self.stop();self.unchanged_payload()
        self.result['peer']=peer.evidence()
        if peer.forbidden or peer.errors:raise AssertionError('Unexpected protocol commands in loopback fixture')
        self.result.update(ok=True,noSubscriberReadbacks=True,oneSharedMeterPoller=True,conditionalConflict=True,
                           idempotentReplay=True,replacementIdentityBound=True,immutablePayload=True,webEnabled=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive',required=True);parser.add_argument('--state-dir',required=True)
    parser.add_argument('--allow-development-build',action='store_true');parser.add_argument('--browser-check',action='store_true')
    args=parser.parse_args();check=LiveCheck(args);check.streams=[];started=time.monotonic()
    try:check.run()
    except (Exception,KeyboardInterrupt) as error:check.result.update(ok=False,error=type(error).__name__+': '+str(error))
    finally:
        for stream in check.streams:
            try:stream.close()
            except Exception as error:check.result.update(ok=False,streamCleanupError=str(error))
        try:check.stop()
        except Exception as error:check.result.update(ok=False,shutdownError=str(error))
        check.result['seconds']=round(time.monotonic()-started,3)
        (check.state/'result.json').write_text(json.dumps(check.result,indent=2)+'\n')
    print(json.dumps(check.result,indent=2));return 0 if check.result['ok'] else 1


if __name__=='__main__':sys.exit(main())
