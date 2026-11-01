package de.drremote.dsp408controller.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.*;
import org.osgi.service.component.annotations.*;
import org.osgi.service.http.*;

/** Serves the bundle's same-origin LAN interface without filesystem access. */
@Component(immediate = true, service = {})
public final class DspWebAppServlet extends HttpServlet {
    static final String HTTP_ALIAS="/app";
    private static final Map<String,String> ASSETS=Map.of(
            "/","text/html","/index.html","text/html","/styles.css","text/css",
            "/app.mjs","text/javascript","/model.mjs","text/javascript","/icon.svg","image/svg+xml");
    private HttpService httpService;
    private boolean registered;

    @Reference void bindHttpService(HttpService service){httpService=service;}
    void unbindHttpService(HttpService service){if(httpService==service)httpService=null;}
    @Activate void activate() throws ServletException,NamespaceException {
        httpService.registerServlet(HTTP_ALIAS,this,null,null);registered=true;
    }
    @Deactivate void deactivate(){
        if(!registered||httpService==null)return;
        try{httpService.unregister(HTTP_ALIAS);}catch(IllegalArgumentException alreadyRemoved){/* Already gone. */}
        finally{registered=false;}
    }
    @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws IOException {
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Referrer-Policy","no-referrer");
        response.setHeader("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
        String path=request.getPathInfo();
        if(path==null||path.isEmpty()){response.sendRedirect(request.getContextPath()+HTTP_ALIAS+"/");return;}
        String type=ASSETS.get(path);
        if(type==null){response.sendError(404);return;}
        String resource="/web"+(path.equals("/")?"/index.html":path);
        try(InputStream stream=getClass().getResourceAsStream(resource)) {
            if(stream==null){response.sendError(404);return;}
            byte[] bytes=stream.readAllBytes();response.setStatus(200);response.setContentType(type);
            response.setCharacterEncoding("UTF-8");response.setContentLength(bytes.length);response.getOutputStream().write(bytes);
        }
    }
}
