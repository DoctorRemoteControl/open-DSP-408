package de.drremote.dsp408controller.runtime;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
        name = "DSP408 Controller",
        description = "Karaf/OSGi configuration for the DSP408 Controller"
)
public @interface Dsp408Configuration {

    @AttributeDefinition(name = "Device registry mode", description = "static (legacy configuration) or managed (persistent HTTP device registry).")
    String registry_mode() default "static";

    @AttributeDefinition(name = "Device registry file", description = "Managed registry JSON path. Empty uses the OSGi bundle data directory.")
    String registry_file() default "";

    @AttributeDefinition(name = "DSP ID")
    String dsp_id() default "main";

    @AttributeDefinition(name = "DSP IP")
    String dsp_ip() default "192.168.0.166";

    @AttributeDefinition(name = "DSP Port")
    int dsp_port() default 9761;

    @AttributeDefinition(
            name = "DSP Library",
            description = "Protocol library selected before the initial dump: fir408, dsp408 or auto."
    )
    String dsp_library() default "fir408";

    @AttributeDefinition(name = "Auto Connect")
    boolean auto_connect() default false;

    @AttributeDefinition(
            name = "Auto Read On Connect",
            description = "Compatibility flag. The server always performs one verified full settings-cache synchronization after connecting."
    )
    boolean auto_read_on_connect() default true;

    @AttributeDefinition(
            name = "Central Meter Poll Interval ms",
            description = "The server is the only DSP meter poller. Clients must read GET /meters."
    )
    long meter_poll_interval_ms() default 200L;

    @AttributeDefinition(name = "Volume Step dB")
    double volume_step_db() default 1.0;

    @AttributeDefinition(
            name = "Additional DSPs",
            description = "Additional DSP definitions as id=ip or id=ip:port, for example fir=192.168.0.166:9761"
    )
    String[] dsps() default {};
}
