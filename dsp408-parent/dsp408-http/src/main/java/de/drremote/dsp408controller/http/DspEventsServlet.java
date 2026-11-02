package de.drremote.dsp408controller.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.*;
import javax.servlet.http.*;
import org.osgi.service.component.annotations.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408.api.*;

/** Exact whiteboard route enables asynchronous streaming alongside the legacy API alias. */
@Component(immediate=true,service=Servlet.class,property={
        "osgi.http.whiteboard.servlet.pattern=/api/v1/events",
        "osgi.http.whiteboard.servlet.name=dsp408-events",
        "osgi.http.whiteboard.servlet.asyncSupported:Boolean=true"})
public final class DspEventsServlet extends HttpServlet {
    @Reference private DspService dsp;
    private DspEventHub hub;
    @Activate void activate(){hub=new DspEventHub(dsp);}
    @Deactivate void deactivate(){if(hub!=null)hub.close();}
    @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws IOException {
        AsyncContext async=null;DspEventClient client=null;
        try{
            String[] ids=request.getParameterValues("dspId");String id=null;
            if(ids!=null){if(ids.length!=1)throw new IllegalArgumentException("Exactly one dspId is allowed");id=DspDeviceDefinitionDto.normalizeId(ids[0]);dsp.forDsp(id);}
            if(!request.isAsyncSupported())throw new UnsupportedOperationException("Asynchronous events are unavailable");
            response.setStatus(200);response.setContentType("text/event-stream");response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no");
            async=request.startAsync();AtomicReference<DspEventHub.Subscription> subscription=new AtomicReference<>();
            client=new DspEventClient(async,()->{var value=subscription.get();if(value!=null)value.close();},System::nanoTime);
            var value=hub.subscribe(id,client);subscription.set(value);if(client.isClosed())value.close();else client.start();
        }catch(Exception failure){
            int status=failure instanceof DspDeviceNotFoundException?404:failure instanceof RejectedExecutionException?503:
                    failure instanceof IllegalArgumentException?422:failure instanceof UnsupportedOperationException?409:500;
            try{
                if(!response.isCommitted()){
                    response.setStatus(status);response.setContentType("application/json");
                    response.getOutputStream().write(new ObjectMapper().writeValueAsString(Map.of("error",status==503?"dsp_overloaded":"events_unavailable","message",status==500?"Event stream could not start.":String.valueOf(failure.getMessage()))).getBytes(StandardCharsets.UTF_8));
                }
            }finally{
                if(client!=null)client.close();else if(async!=null)try{async.complete();}catch(IllegalStateException ignored){/* Already cancelled. */}
            }
        }
    }
}
