package de.drremote.dsp408controller.matrix;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
        name = "DSP408 Matrix Bot",
        description = "Karaf/OSGi configuration for the Matrix bot"
)
public @interface MatrixBotConfiguration {

    @AttributeDefinition(name = "Enabled")
    boolean enabled() default false;

    @AttributeDefinition(name = "Matrix URL")
    String matrix_url() default "";

    @AttributeDefinition(name = "Access Token")
    String access_token() default "";

    @AttributeDefinition(name = "Control Room ID")
    String control_room_id() default "";

    @AttributeDefinition(name = "Admin Room ID (deprecated)")
    String admin_room_id() default "";

    @AttributeDefinition(name = "Volume Room ID")
    String volume_room_id() default "";

    @AttributeDefinition(name = "Limiter Warning Room ID")
    String warning_room_id() default "";

    @AttributeDefinition(name = "Machine Room ID")
    String machine_room_id() default "";

    @AttributeDefinition(name = "Allowed Users")
    String[] allowed_users() default {};

    @AttributeDefinition(name = "Admin Users")
    String[] admin_users() default {};

    @AttributeDefinition(name = "Machine Users")
    String[] machine_users() default {};

    @AttributeDefinition(name = "Sync Timeout ms")
    int sync_timeout_ms() default 30000;

    @AttributeDefinition(name = "Reconnect Delay ms")
    int reconnect_delay_ms() default 3000;

    @AttributeDefinition(name = "Limiter Warnings Enabled")
    boolean limiter_warnings_enabled() default true;

    @AttributeDefinition(name = "Limiter Warning Poll ms")
    int limiter_warning_poll_ms() default 1000;

    @AttributeDefinition(name = "Limiter Warning Cooldown ms")
    int limiter_warning_cooldown_ms() default 15000;

    @AttributeDefinition(name = "Connect DSP On Start")
    boolean connect_dsp_on_start() default true;
}
