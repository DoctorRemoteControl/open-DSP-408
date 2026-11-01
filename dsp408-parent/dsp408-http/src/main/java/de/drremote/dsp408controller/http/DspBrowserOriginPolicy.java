package de.drremote.dsp408controller.http;

import java.net.URI;
import javax.servlet.http.HttpServletRequest;

/** Browser cross-origin mutation protection; native clients without Origin remain supported. */
final class DspBrowserOriginPolicy {
    private DspBrowserOriginPolicy() { }
    static boolean allowed(HttpServletRequest request) {
        String origin=request.getHeader("Origin");
        if(origin==null)return true;
        try {
            URI source=new URI(origin);
            if(source.getHost()==null||source.getUserInfo()!=null||source.getRawQuery()!=null
                    ||source.getFragment()!=null||(source.getRawPath()!=null&&!source.getRawPath().isEmpty()))return false;
            String scheme=source.getScheme();
            if(!("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme)))return false;
            int port=source.getPort()<0?("https".equalsIgnoreCase(scheme)?443:80):source.getPort();
            return scheme.equalsIgnoreCase(request.getScheme())
                    &&host(source.getHost()).equalsIgnoreCase(host(request.getServerName()))
                    &&port==request.getServerPort();
        } catch(Exception invalid){return false;}
    }
    private static String host(String value){return value==null?"":value.replace("[","").replace("]","");}
}
