package de.drremote.dsp408controller.http;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.servlet.*;

/** One bounded, nonblocking event response. Browser I/O never runs on the sampler. */
final class DspEventClient implements WriteListener,AsyncListener,AutoCloseable {
    private static final int CHUNK=2048;
    private static final long STALLED_NANOS=30_000_000_000L;
    private final AsyncContext context;
    private final ServletOutputStream output;
    private final Runnable onClosed;
    private final LongSupplier clock;
    private final Object lock=new Object();
    private final LinkedHashMap<String,DspEventFrame> pending=new LinkedHashMap<>();
    private final AtomicBoolean draining=new AtomicBoolean(),closed=new AtomicBoolean();
    private DspEventFrame current;
    private int offset;
    private volatile boolean started,finishing;
    private volatile long lastProgress;

    DspEventClient(AsyncContext context,Runnable onClosed,LongSupplier clock) throws IOException {
        this.context=context;this.onClosed=onClosed;this.clock=clock;this.lastProgress=clock.getAsLong();
        this.output=context.getResponse().getOutputStream();context.setTimeout(0);context.addListener(this);
    }
    void start(){if(closed.get())return;started=true;output.setWriteListener(this);kick();}
    void offer(DspEventFrame frame){
        synchronized(lock){if(closed.get()||finishing)return;pending.put(frame.kind(),frame);}
        kick();
    }
    void finish(DspEventFrame frame){
        synchronized(lock){if(closed.get()||finishing)return;finishing=true;pending.clear();pending.put(frame.kind(),frame);}
        kick();
    }
    boolean isClosed(){return closed.get();}
    void expireIfStalled(long now){
        boolean hasWork;synchronized(lock){hasWork=current!=null||!pending.isEmpty();}
        if(hasWork&&now-lastProgress>STALLED_NANOS)close();
    }
    private void kick(){
        if(!started||closed.get()||!draining.compareAndSet(false,true))return;
        try{context.start(this::drain);}catch(IllegalStateException failure){draining.set(false);close();}
    }
    private void drain(){
        try{
            while(!closed.get()){
                DspEventFrame frame;
                synchronized(lock){
                    if(current==null&&!pending.isEmpty()){
                        var first=pending.entrySet().iterator();current=first.next().getValue();first.remove();offset=0;
                    }
                    frame=current;
                }
                if(frame==null){if(finishing)close();return;}
                if(!output.isReady())return;
                if(offset<frame.bytes().length){
                    int length=Math.min(CHUNK,frame.bytes().length-offset);
                    output.write(frame.bytes(),offset,length);offset+=length;lastProgress=clock.getAsLong();
                }else{
                    output.flush();lastProgress=clock.getAsLong();
                    synchronized(lock){current=null;offset=0;}
                }
            }
        }catch(IOException|RuntimeException failure){close();}
        finally{
            draining.set(false);
            boolean more;synchronized(lock){more=current!=null||!pending.isEmpty();}
            if(!closed.get()&&more){
                try{if(output.isReady())kick();}catch(RuntimeException failure){close();}
            }
        }
    }
    @Override public void onWritePossible(){kick();}
    @Override public void onError(Throwable error){close();}
    @Override public void onComplete(AsyncEvent event){close();}
    @Override public void onTimeout(AsyncEvent event){close();}
    @Override public void onError(AsyncEvent event){close();}
    @Override public void onStartAsync(AsyncEvent event){close();}
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        synchronized(lock){pending.clear();current=null;}
        try{context.complete();}catch(IllegalStateException alreadyCompleted){/* Container completed cancellation. */}
        finally{onClosed.run();}
    }
}
