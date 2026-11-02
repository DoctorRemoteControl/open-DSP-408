package de.drremote.dsp408controller.http;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import de.drremote.dsp408.api.*;

/** Shared RAM sampling per subscribed target, with bounded latest-only delivery. */
final class DspEventHub implements AutoCloseable {
    private static final int MAX_CLIENTS=128;
    private final DspService root;
    private final LongSupplier clock;
    private final boolean automatic;
    private final Map<DspEventClient,Subscription> clients=new LinkedHashMap<>();
    private final Map<String,Target> targets=new LinkedHashMap<>();
    private final String epoch=UUID.randomUUID().toString();
    private long sequence,lastDevicesAt,lastHeartbeatAt;
    private Object devices;
    private DspEventFrame devicesFrame;
    private ScheduledExecutorService sampler;
    private boolean closed;
    DspEventHub(DspService root){this(root,System::nanoTime,true);}
    DspEventHub(DspService root,LongSupplier clock,boolean automatic){this.root=root;this.clock=clock;this.automatic=automatic;}

    synchronized Subscription subscribe(String requested,DspEventClient client){
        if(closed)throw new IllegalStateException("Event publisher is closed");
        if(clients.size()>=MAX_CLIENTS)throw new RejectedExecutionException("Event subscriber capacity is full");
        String id=requested==null?null:DspDeviceDefinitionDto.normalizeId(requested);
        Target target=null;
        if(id!=null){
            DspService service=root.forDsp(id);
            target=targets.get(id);
            if(target!=null&&target.service!=service){removeTarget(target);target=null;}
            if(target==null){target=new Target(id,service);readTarget(target);targets.put(id,target);}
        }
        if(devicesFrame==null)readDevices();
        Subscription subscription=new Subscription(client,target);clients.put(client,subscription);
        if(target!=null)target.subscribers++;
        client.offer(devicesFrame);
        if(target!=null){client.offer(target.stateFrame);client.offer(target.metersFrame);}
        if(automatic&&sampler==null){
            sampler=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"dsp408-event-sampler");thread.setDaemon(true);return thread;});
            sampler.scheduleWithFixedDelay(this::sample,200,200,TimeUnit.MILLISECONDS);
        }
        return subscription;
    }
    private void sample(){try{tick();}catch(RuntimeException failure){closeSubscribers();}}
    synchronized void tick(){
        if(closed)return;
        long now=clock.getAsLong();
        for(Subscription sub:List.copyOf(clients.values())){
            sub.client.expireIfStalled(now);if(sub.client.isClosed())sub.close();
        }
        if(clients.isEmpty())return;
        if(now-lastDevicesAt>=1_000_000_000L)readDevices();
        for(Target target:List.copyOf(targets.values())){
            DspService selected;
            try{selected=root.forDsp(target.id);}catch(IllegalArgumentException missing){removeTarget(target);continue;}
            if(selected!=target.service){removeTarget(target);continue;}
            ControlStateDto previousState=target.state;Object previousMeters=target.meters;
            readTarget(target);
            for(Subscription sub:List.copyOf(clients.values()))if(sub.target==target){
                if(!Objects.equals(previousState,target.state))sub.client.offer(target.stateFrame);
                if(!Objects.equals(previousMeters,target.meters))sub.client.offer(target.metersFrame);
            }
        }
        if(now-lastHeartbeatAt>=10_000_000_000L){
            DspEventFrame frame=frame("heartbeat",Map.of("serverTimeEpochMs",System.currentTimeMillis()));
            for(Subscription sub:List.copyOf(clients.values()))sub.client.offer(frame);lastHeartbeatAt=now;
        }
    }
    private void readDevices(){
        Object next=Map.of("defaultDsp",root.dspId(),"dsps",root.getDspInstances());
        if(!Objects.equals(devices,next)){
            devices=next;devicesFrame=frame("devices",next);for(Subscription sub:List.copyOf(clients.values()))sub.client.offer(devicesFrame);
        }
        lastDevicesAt=clock.getAsLong();
    }
    private void readTarget(Target target){
        ControlStateDto state=target.service.getControlState();
        Object meters=Map.of("dspId",target.id,"connected",target.service.isConnected(),"meters",target.service.getMeters());
        if(!Objects.equals(target.state,state)){target.state=state;target.stateFrame=frame("state",state);}
        if(!Objects.equals(target.meters,meters)){target.meters=meters;target.metersFrame=frame("meters",meters);}
    }
    private void removeTarget(Target target){
        targets.remove(target.id,target);
        DspEventFrame removed=frame("removed",Map.of("dspId",target.id,"instanceId",target.state.instanceId(),"message","Selected device was removed or replaced."));
        for(Subscription sub:List.copyOf(clients.values()))if(sub.target==target)sub.client.finish(removed);
    }
    private DspEventFrame frame(String kind,Object data){return DspEventFrame.json(kind,epoch+"."+(++sequence),data);}
    private synchronized void closeSubscribers(){for(Subscription sub:List.copyOf(clients.values()))sub.close();}
    @Override public synchronized void close(){if(closed)return;closed=true;closeSubscribers();targets.clear();stopSampler();}
    private void stopSampler(){if(sampler!=null){sampler.shutdownNow();sampler=null;}}
    final class Subscription implements AutoCloseable {
        final DspEventClient client;final Target target;boolean removed;
        Subscription(DspEventClient client,Target target){this.client=client;this.target=target;}
        @Override public void close(){synchronized(DspEventHub.this){
            if(removed)return;removed=true;clients.remove(client);
            if(target!=null&&--target.subscribers==0)targets.remove(target.id,target);
            if(clients.isEmpty())stopSampler();
        }client.close();}
    }
    private static final class Target {
        final String id;final DspService service;int subscribers;
        ControlStateDto state;Object meters;DspEventFrame stateFrame,metersFrame;
        Target(String id,DspService service){this.id=id;this.service=service;}
    }
}
