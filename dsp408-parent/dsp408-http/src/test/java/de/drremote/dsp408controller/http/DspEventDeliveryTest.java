package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.*;
import javax.servlet.*;
import de.drremote.dsp408.api.*;
import org.junit.jupiter.api.Test;

class DspEventDeliveryTest {
    @Test void blockedBrowserReceivesOnlyLatestTelemetryWithoutUnboundedWork() throws Exception {
        Harness h=new Harness(); h.ready=false;
        DspEventClient client=new DspEventClient(h.context,()->{},()->0L); client.start();
        for(int i=0;i<1000;i++)client.offer(DspEventFrame.json("meters","m"+i,Map.of("sample",i)));
        assertEquals(1,h.tasks.size(),"Only one drain task may be queued per client");
        h.run(); assertEquals("",h.text());
        h.ready=true;h.listener.onWritePossible();h.run();
        assertTrue(h.text().contains("\"sample\":999"));
        assertEquals(1,h.text().split("event: meters",-1).length-1);
        client.close();
    }
    @Test void outputErrorAndAsyncCancellationReleaseSubscriptionOnce() throws Exception {
        Harness h=new Harness();AtomicInteger closes=new AtomicInteger();
        DspEventClient client=new DspEventClient(h.context,closes::incrementAndGet,()->0L);client.start();
        h.listener.onError(new IOException("gone"));client.onComplete(new AsyncEvent(h.context));client.close();
        assertEquals(1,closes.get());assertEquals(1,h.completions);
        client.offer(DspEventFrame.json("meters","late",Map.of()));h.run();assertEquals("",h.text());
    }
    @Test void terminalRemovalFlushesThenCompletes() throws Exception {
        Harness h=new Harness();DspEventClient client=new DspEventClient(h.context,()->{},()->0L);client.start();
        client.offer(DspEventFrame.json("meters","old",Map.of()));
        client.finish(DspEventFrame.json("removed","last",Map.of("dspId","main")));h.run();
        assertTrue(h.text().contains("event: removed"));assertFalse(h.text().contains("event: meters"));assertEquals(1,h.completions);
    }
    @Test void permanentlyBlockedOutputIsClosedWithoutBlockingSampler() throws Exception {
        Harness h=new Harness();h.ready=false;DspEventClient client=new DspEventClient(h.context,()->{},()->0L);client.start();
        client.offer(DspEventFrame.json("state","s",Map.of()));h.run();
        client.expireIfStalled(31_000_000_000L);assertTrue(client.isClosed());assertEquals(1,h.completions);
    }
    @Test void subscribersShareCachedSamplingAndReceiveFreshReconnectState() throws Exception {
        Provider p=new Provider();try(DspEventHub hub=new DspEventHub(p.root,()->0L,false)) {
            Harness a=new Harness(),b=new Harness();DspEventClient ca=client(a),cb=client(b);
            try(var sa=hub.subscribe("main",ca);var sb=hub.subscribe("main",cb)) {
                ca.start();cb.start();a.run();b.run();assertEquals(1,p.meterReads.get());assertEquals(1,p.stateReads.get());
                p.observed=200;hub.tick();a.run();b.run();assertEquals(2,p.meterReads.get());assertEquals(2,p.stateReads.get());
                assertTrue(a.text().contains("\"observedAtEpochMs\":200"));assertTrue(b.text().contains("\"observedAtEpochMs\":200"));
            }
            Harness c=new Harness();DspEventClient cc=client(c);try(var s=hub.subscribe("main",cc)){cc.start();c.run();assertTrue(c.text().contains("event: state"));}
        }
    }
    @Test void replacementWithSameIdTerminatesOldTargetInsteadOfRetargeting() throws Exception {
        Provider p=new Provider();try(DspEventHub hub=new DspEventHub(p.root,()->0L,false)) {
            Harness h=new Harness();DspEventClient c=client(h);try(var s=hub.subscribe("main",c)) {
                c.start();h.run();p.target=p.service("replacement");hub.tick();h.run();
                assertTrue(h.text().contains("event: removed"));assertFalse(h.text().contains("\"instanceId\":\"replacement\""));assertTrue(c.isClosed());
            }
        }
    }
    @Test void unknownDeviceAndCapacityFailBeforeCreatingMoreSubscriptions() throws Exception {
        Provider p=new Provider();try(DspEventHub hub=new DspEventHub(p.root,()->0L,false)) {
            assertThrows(DspDeviceNotFoundException.class,()->hub.subscribe("missing",client(new Harness())));
            for(int i=0;i<128;i++)hub.subscribe(null,client(new Harness()));
            assertThrows(RejectedExecutionException.class,()->hub.subscribe(null,client(new Harness())));
        }
    }
    @Test void emptyDeviceListStillHasADevicesOnlyStream() throws Exception {
        Provider p=new Provider();p.present=false;try(DspEventHub hub=new DspEventHub(p.root,()->0L,false)) {
            Harness h=new Harness();DspEventClient c=client(h);try(var s=hub.subscribe(null,c)) {
                c.start();h.run();assertTrue(h.text().contains("\"dsps\":[]"));assertFalse(h.text().contains("event: state"));
            }
        }
    }
    @Test void cancelledSubscriberCannotInterruptOtherDevicesOrHeartbeatDelivery() throws Exception {
        for(boolean heartbeatOnly:new boolean[]{false,true}) {
            Provider p=new Provider();AtomicLong now=new AtomicLong();
            try(DspEventHub hub=new DspEventHub(p.root,now::get,false)) {
                Harness first=new Harness(),second=new Harness();
                AtomicReference<DspEventHub.Subscription> owned=new AtomicReference<>();
                DspEventClient cancelled=new DspEventClient(first.context,()->{var sub=owned.get();if(sub!=null)sub.close();},now::get);
                DspEventClient survivor=client(second);
                try(var a=hub.subscribe(null,cancelled);var b=hub.subscribe(null,survivor)) {
                    owned.set(a);cancelled.start();survivor.start();first.run();second.run();
                    first.failScheduling=true;now.set(heartbeatOnly?11_000_000_000L:1_100_000_000L);
                    if(!heartbeatOnly)p.present=false;
                    assertDoesNotThrow(hub::tick);second.run();assertFalse(survivor.isClosed());
                    assertTrue(second.text().contains(heartbeatOnly?"event: heartbeat":"\"dsps\":[]"));
                }
            }
        }
    }
    @Test void failedErrorResponseStillCompletesTheStartedAsyncRequest() throws Exception {
        AtomicInteger completed=new AtomicInteger();
        javax.servlet.http.HttpServletResponse broken=(javax.servlet.http.HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{javax.servlet.http.HttpServletResponse.class},(p,m,a)->{
            if(m.getName().equals("getOutputStream"))throw new IOException("connection failed");
            return m.getReturnType()==boolean.class?false:null;
        });
        AsyncContext context=(AsyncContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{AsyncContext.class},(p,m,a)->{
            if(m.getName().equals("getResponse"))return broken;
            if(m.getName().equals("complete"))completed.incrementAndGet();return null;
        });
        javax.servlet.http.HttpServletRequest request=(javax.servlet.http.HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{javax.servlet.http.HttpServletRequest.class},(p,m,a)->switch(m.getName()){
            case "isAsyncSupported"->true;case "startAsync"->context;default->null;
        });
        DspEventsServlet servlet=new DspEventsServlet();var field=DspEventsServlet.class.getDeclaredField("dsp");field.setAccessible(true);field.set(servlet,new Provider().root);servlet.activate();
        try{assertThrows(IOException.class,()->servlet.doGet(request,broken));}finally{servlet.deactivate();}
        assertEquals(1,completed.get(),"Output errors must not leak an async request");
    }
    private static DspEventClient client(Harness h) throws IOException {return new DspEventClient(h.context,()->{},()->0L);}
    static class Harness {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        boolean ready=true,failScheduling;WriteListener listener;int completions;
        final ServletOutputStream output=new ServletOutputStream(){
            public boolean isReady(){return ready;}public void setWriteListener(WriteListener l){listener=l;}
            public void write(int b){if(!ready)throw new AssertionError("Blocking write");bytes.write(b);}
            public void write(byte[] b,int off,int len){if(!ready)throw new AssertionError("Blocking write");bytes.write(b,off,len);}
        };
        final ServletResponse response=(ServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ServletResponse.class},(p,m,a)->m.getName().equals("getOutputStream")?output:null);
        final AsyncContext context=(AsyncContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{AsyncContext.class},(p,m,a)->{
            return switch(m.getName()){case "getResponse"->response;case "start"->{if(failScheduling)throw new IllegalStateException("cancelled");tasks.add((Runnable)a[0]);yield null;}case "complete"->{completions++;yield null;}default->null;};
        });
        void run(){int count=0;while(!tasks.isEmpty()){if(++count>50)throw new AssertionError("Drain spun without progress");tasks.remove().run();}}
        String text(){return bytes.toString(StandardCharsets.UTF_8);}
    }
    static class Provider {
        final AtomicInteger meterReads=new AtomicInteger(),stateReads=new AtomicInteger();boolean present=true;long observed;
        DspService target=service("original");
        final DspService root=(DspService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DspService.class},(p,m,a)->switch(m.getName()){
            case "dspId"->"";case "getDspInstances"->present?List.of(new DspInstanceDto("main","127.0.0.1",9761,false,false,null)):List.of();
            case "forDsp"->{if(!present||!a[0].equals("main"))throw new DspDeviceNotFoundException("missing");yield target;}
            default->throw new AssertionError("Unexpected root call: "+m);
        });
        DspService service(String instance){return (DspService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DspService.class},(p,m,a)->switch(m.getName()){
            case "dspId"->"main";case "isConnected"->false;
            case "getControlState"->{stateReads.incrementAndGet();yield new ControlStateDto("main",instance,1,false,List.of(),null,SettingsCacheDto.empty());}
            case "getMeters"->{meterReads.incrementAndGet();yield new MeterStateDto(List.of(),null,false,null,null,List.of(),observed,false,null);}
            default->throw new AssertionError("Live subscriptions must not call DSP commands: "+m);
        });}
    }
}
