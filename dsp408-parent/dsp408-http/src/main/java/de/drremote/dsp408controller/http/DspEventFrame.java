package de.drremote.dsp408controller.http;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Immutable encoded frame, shared by subscribers; telemetry can be coalesced by kind. */
record DspEventFrame(String kind, byte[] bytes) {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> KINDS=Set.of("devices","state","meters","heartbeat","removed");
    static DspEventFrame json(String kind,String id,Object value) {
        if(!KINDS.contains(kind)||id==null||!id.matches("[A-Za-z0-9._-]{1,128}"))throw new IllegalArgumentException("Invalid event kind or cursor");
        try{return new DspEventFrame(kind,("id: "+id+"\nevent: "+kind+"\ndata: "+JSON.writeValueAsString(value)+"\n\n").getBytes(StandardCharsets.UTF_8));}
        catch(JsonProcessingException error){throw new IllegalStateException("Cannot encode cached event",error);}
    }
}
