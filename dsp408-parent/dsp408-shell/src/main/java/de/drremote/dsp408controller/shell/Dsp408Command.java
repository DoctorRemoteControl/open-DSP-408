package de.drremote.dsp408controller.shell;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import de.drremote.dsp408.api.DspService;

@Component(
        service = Dsp408Command.class,
        property = {
                "osgi.command.scope=dsp408",
                "osgi.command.description=DSP408 commands",

                "osgi.command.function=help",
                "osgi.command.function=h",
                "osgi.command.function=dsps",
                "osgi.command.function=select",
                "osgi.command.function=current",
                "osgi.command.function=dsp",

                "osgi.command.function=connect",
                "osgi.command.function=disconnect",
                "osgi.command.function=reconnect",
                "osgi.command.function=connected",

                "osgi.command.function=state",
                "osgi.command.function=status",
                "osgi.command.function=channels",
                "osgi.command.function=get",
                "osgi.command.function=gain",
                "osgi.command.function=mute",
                "osgi.command.function=unmute",
                "osgi.command.function=name",
                "osgi.command.function=phase",
                "osgi.command.function=delay",
                "osgi.command.function=delayunit",

                "osgi.command.function=deviceinfo",
                "osgi.command.function=info",
                "osgi.command.function=login",
                "osgi.command.function=loadpreset",
                "osgi.command.function=readpresetname",
                "osgi.command.function=meters",
                "osgi.command.function=meterstate",
                "osgi.command.function=meter",

                "osgi.command.function=blocks",
                "osgi.command.function=readblock",
                "osgi.command.function=showblock",
                "osgi.command.function=scanblocks",
                "osgi.command.function=refresh",

                "osgi.command.function=raw",

                "osgi.command.function=route",
                "osgi.command.function=xgain",

                "osgi.command.function=xhpset",
                "osgi.command.function=xhpfreq",
                "osgi.command.function=xhpslope",
                "osgi.command.function=xhpbypass",
                "osgi.command.function=xlpset",
                "osgi.command.function=xlpfreq",
                "osgi.command.function=xlpslope",
                "osgi.command.function=xlpbypass",

                "osgi.command.function=firmode",
                "osgi.command.function=firgen",
                "osgi.command.function=firset",
                "osgi.command.function=firupload",

                "osgi.command.function=opeqset",
                "osgi.command.function=opeqfreq",
                "osgi.command.function=opeqq",
                "osgi.command.function=opeqqraw",
                "osgi.command.function=opeqgain",
                "osgi.command.function=opeqgcode",
                "osgi.command.function=opeqtype",
                "osgi.command.function=peqf",
                "osgi.command.function=peqqraw",
                "osgi.command.function=peqgcode",

                "osgi.command.function=ipeqset",
                "osgi.command.function=ipeqfreq",
                "osgi.command.function=ipeqq",
                "osgi.command.function=ipeqgain",
                "osgi.command.function=ipeqtype",
                "osgi.command.function=ipeqbypass",

                "osgi.command.function=igeq",

                "osgi.command.function=gateset",
                "osgi.command.function=gatethreshold",
                "osgi.command.function=gatehold",
                "osgi.command.function=gateattack",
                "osgi.command.function=gaterelease",

                "osgi.command.function=compset",
                "osgi.command.function=compget",
                "osgi.command.function=limitset",
                "osgi.command.function=limitget",

                "osgi.command.function=tone",
                "osgi.command.function=toneon",
                "osgi.command.function=tonesource",
                "osgi.command.function=tonefreq",
                "osgi.command.function=toneraw",
                "osgi.command.function=toneoff",

                "osgi.command.function=volume",
                "osgi.command.function=volstatus",
                "osgi.command.function=volrefresh",
                "osgi.command.function=volup",
                "osgi.command.function=voldown",
                "osgi.command.function=volmute",
                "osgi.command.function=volunmute",
                "osgi.command.function=volset",
                "osgi.command.function=volstep",

                // legacy fallback
                "osgi.command.function=cmd"
        }
)
public final class Dsp408Command {

    private DspService manager;
    private DspService service;

    @Reference
    void bindService(DspService service) {
        this.manager = service;
        this.service = service;
    }

    void unbindService(DspService service) {
        if (this.manager == service) {
            this.manager = null;
            this.service = null;
        }
    }

    public Object help() {
        return """
                DSP408 Karaf commands

                Quick start:
                  dsp408:dsps
                  dsp408:select main
                  dsp408:current
                  dsp408:dsp fir408 state
                  dsp408:connected
                  dsp408:connect
                  dsp408:state
                  dsp408:scanblocks

                Channels:
                  Inputs:  ina, inb, inc, ind
                  Outputs: out1 .. out8

                Connection:
                  dsp408:dsps
                  dsp408:select <dsp-id>
                  dsp408:current
                  dsp408:dsp <dsp-id> <command> [args...]
                  dsp408:connect
                  dsp408:disconnect
                  dsp408:reconnect
                  dsp408:connected

                State / channels:
                  dsp408:state
                  dsp408:status
                  dsp408:channels
                  dsp408:get <channel>
                  dsp408:gain <channel> <db>
                  dsp408:mute <channel>
                  dsp408:unmute <channel>
                  dsp408:name <channel> <ascii-name>
                  dsp408:phase <channel> <0|180|normal|inverted>
                  dsp408:delay <channel> <ms>
                  dsp408:delayunit <ms|m|ft>

                Device:
                  dsp408:deviceinfo
                  dsp408:info
                  dsp408:login <1234>
                  dsp408:loadpreset <F00|U01..U20>
                  dsp408:loadpreset <0..20>
                  dsp408:readpresetname <0..19>
                  dsp408:meters                  (read DSP now)
                  dsp408:meterstate              (cached meters)
                  dsp408:meter <channel>          (cached channel meter)

                Blocks:
                  dsp408:blocks
                  dsp408:readblock <hex>
                  dsp408:showblock <hex>
                  dsp408:scanblocks
                  dsp408:refresh

                Debug:
                  dsp408:raw <hex payload>

                Matrix:
                  dsp408:route <outX> <inA|inB|inC|inD>
                  dsp408:xgain <outX> <inA|inB|inC|inD> <db>

                  Examples:
                    dsp408:route out1 inA
                    dsp408:xgain out3 inA -6
                    dsp408:xgain out3 inB -6

                  Note: route selects one main input. Use xgain to set individual
                  matrix crosspoint gains.

                Crossover:
                  dsp408:xhpset <channel> <hz> <slope> <on|off>
                  dsp408:xhpfreq <channel> <hz>
                  dsp408:xhpslope <channel> <slope>
                  dsp408:xhpbypass <channel> <on|off>
                  dsp408:xlpset <channel> <hz> <slope> <on|off>
                  dsp408:xlpfreq <channel> <hz>
                  dsp408:xlpslope <channel> <slope>
                  dsp408:xlpbypass <channel> <on|off>

                  Slopes:
                    BW_6,  BS_6,  BW_12, BS_12, LR_12
                    BW_18, BS_18, BW_24, BS_24, LR_24
                    BW_30, BS_30, BW_36, BS_36, LR_36
                    BW_42, BS_42, BW_48, BS_48, LR_48

                  Examples:
                    dsp408:xhpset out1 150 LR_24 on
                    dsp408:xlpset out3 150 LR_24 on

                Output PEQ:
                  dsp408:opeqset <outX> <band> <type> <hz> <q> <gainDb>
                  dsp408:opeqfreq <outX> <band> <hz>
                  dsp408:opeqq <outX> <band> <q>
                  dsp408:opeqqraw <outX> <band> <0..100>
                  dsp408:opeqgain <outX> <band> <gainDb>
                  dsp408:opeqgcode <outX> <band> <code>
                  dsp408:opeqtype <outX> <band> <type>
                  legacy aliases: peqf, peqqraw, peqgcode

                FIR408:
                  dsp408:firmode <outX> <iir|fir>
                  dsp408:firgen <outX> <type> <window> <hpHz> <lpHz> <taps>
                  dsp408:firupload <channel> <name> <file|coefficients>

                Input PEQ / GEQ:
                  dsp408:ipeqset <inX> <band> <type> <hz> <q> <gainDb> <on|off>
                  dsp408:ipeqfreq <inX> <band> <hz>
                  dsp408:ipeqq <inX> <band> <q>
                  dsp408:ipeqgain <inX> <band> <gainDb>
                  dsp408:ipeqtype <inX> <band> <type>
                  dsp408:ipeqbypass <inX> <band> <on|off>
                  dsp408:igeq <inX> <band> <gainDb>

                Dynamics:
                  dsp408:gateset <inX> <thresholdDb> <holdMs> <attackMs> <releaseMs>
                  dsp408:gatethreshold <inX> <thresholdDb>
                  dsp408:gatehold <inX> <holdMs>
                  dsp408:gateattack <inX> <attackMs>
                  dsp408:gaterelease <inX> <releaseMs>
                  dsp408:compset <inX> <thresholdDb> <ratio> <attackMs> <releaseMs> <kneeDb>
                  dsp408:compget <inX>
                  dsp408:limitset <outX> <thresholdDb> <attackMs> <releaseMs>
                  dsp408:limitget <outX>

                Test tone:
                  dsp408:tone <hz>
                  dsp408:toneon <hz>
                  dsp408:tonesource <analog|pink|white|sine>
                  dsp408:tonefreq <hz>
                  dsp408:toneraw <0..30>
                  dsp408:toneoff

                Volume room helpers:
                  dsp408:volume
                  dsp408:volume help
                  dsp408:volume status
                  dsp408:volume refresh
                  dsp408:volume up
                  dsp408:volume down
                  dsp408:volume mute
                  dsp408:volume unmute
                  dsp408:volume set -10
                  dsp408:volume step +1

                Shortcuts:
                  dsp408:volstatus
                  dsp408:volrefresh
                  dsp408:volup
                  dsp408:voldown
                  dsp408:volmute
                  dsp408:volunmute
                  dsp408:volset -10
                  dsp408:volstep +1

                Legacy:
                  dsp408:cmd state
                  dsp408:cmd gain ina -12

                Tip:
                  DTO output such as ChannelDto[...] is command output only. Do not
                  paste fields like gainDb=... back into the Karaf shell.
                """;
    }

    public Object h() {
        return help();
    }

    public Object dsps() {
        return manager.getDspInstances();
    }

    public Object select(String... args) {
        requireArgs(args, 1, "Usage: dsp408:select <dsp-id>");
        service = manager.forDsp(args[0]);
        return "Selected DSP " + service.dspId();
    }

    public Object current() {
        return "Selected DSP " + service.dspId();
    }

    public Object dsp(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:dsp <dsp-id> <command> [args...]");
        DspService target = manager.forDsp(args[0]);
        return dispatch(target, tail(args, 1));
    }

    public Object connect() throws Exception {
        return service.connect();
    }

    public Object disconnect() {
        service.disconnect();
        return "Disconnected.";
    }

    public Object reconnect() throws Exception {
        return service.reconnect();
    }

    public Object connected() {
        return service.isConnected() ? "yes" : "no";
    }

    public Object state() throws Exception {
        return run("state");
    }

    public Object status() throws Exception {
        return run("state");
    }

    public Object channels() throws Exception {
        return service.getChannels();
    }

    public Object get(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:get <channel>");
        return service.getChannel(args[0]);
    }

    public Object gain(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:gain <channel> <db>");
        return service.setGain(args[0], parseDouble(args[1]));
    }

    public Object mute(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:mute <channel>");
        return service.mute(args[0]);
    }

    public Object unmute(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:unmute <channel>");
        return service.unmute(args[0]);
    }

    public Object name(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:name <channel> <ascii-name>");
        return service.setChannelName(args[0], args[1]);
    }

    public Object phase(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:phase <channel> <0|180|normal|inverted>");
        return service.setPhase(args[0], parsePhase(args[1]));
    }

    public Object delay(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:delay <channel> <ms>");
        return service.setDelay(args[0], parseDouble(args[1]));
    }

    public Object delayunit(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:delayunit <ms|m|ft>");
        return service.setDelayUnit(args[0]);
    }

    public Object deviceinfo() throws Exception {
        return service.getDeviceInfo();
    }

    public Object info() throws Exception {
        return service.getDeviceInfo();
    }

    public Object login(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:login <1234>");
        service.loginPin(args[0]);
        return "Login PIN sent.";
    }

    public Object loadpreset(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:loadpreset <F00|U01..U20|0..20>");
        if (args[0].matches("\\d+")) {
            return service.loadPreset(Integer.parseInt(args[0]));
        }
        return service.loadPreset(args[0]);
    }

    public Object readpresetname(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:readpresetname <0..19>");
        return service.readPresetName(Integer.parseInt(args[0]));
    }

    public Object meters() throws Exception {
        return service.requestRuntimeMeters();
    }

    public Object meterstate() {
        return service.getMeters();
    }

    public Object meter(String... args) {
        requireArgs(args, 1, "Usage: dsp408:meter <channel>");
        return service.getMeter(args[0]);
    }

    public Object blocks() throws Exception {
        return service.getCachedBlockIndices();
    }

    public Object readblock(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:readblock <hex>");
        return service.readBlock(args[0]);
    }

    public Object showblock(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:showblock <hex>");
        return service.getCachedBlock(args[0]);
    }

    public Object scanblocks() throws Exception {
        return service.scanBlocks();
    }

    public Object refresh() throws Exception {
        return service.scanBlocks();
    }

    public Object raw(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:raw <hex payload>");
        return service.sendRawHex(join(args));
    }

    public Object route(String... args) throws Exception {
        requireExactArgs(args, 2, "Usage: dsp408:route <outX> <inA|inB|inC|inD>");
        return service.setMatrixRoute(args[0], args[1]);
    }

    public Object xgain(String... args) throws Exception {
        requireExactArgs(args, 3, "Usage: dsp408:xgain <outX> <inA|inB|inC|inD> <db>");
        return service.setMatrixCrosspointGain(args[0], args[1], parseDouble(args[2]));
    }

    public Object xhpset(String... args) throws Exception {
        requireArgs(args, 4, "Usage: dsp408:xhpset <channel> <hz> <slope> <on|off>");
        return service.setCrossoverHighPass(args[0], parseDouble(args[1]), args[2], parseOnOff(args[3]));
    }

    public Object xhpfreq(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:xhpfreq <channel> <hz>");
        return service.setCrossoverHighPassFrequency(args[0], parseDouble(args[1]));
    }

    public Object xhpslope(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:xhpslope <channel> <slope>");
        return service.setCrossoverHighPassSlope(args[0], args[1]);
    }

    public Object xhpbypass(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:xhpbypass <channel> <on|off>");
        return service.setCrossoverHighPassBypass(args[0], parseOnOff(args[1]));
    }

    public Object xlpset(String... args) throws Exception {
        requireArgs(args, 4, "Usage: dsp408:xlpset <channel> <hz> <slope> <on|off>");
        return service.setCrossoverLowPass(args[0], parseDouble(args[1]), args[2], parseOnOff(args[3]));
    }

    public Object xlpfreq(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:xlpfreq <channel> <hz>");
        return service.setCrossoverLowPassFrequency(args[0], parseDouble(args[1]));
    }

    public Object xlpslope(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:xlpslope <channel> <slope>");
        return service.setCrossoverLowPassSlope(args[0], args[1]);
    }

    public Object xlpbypass(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:xlpbypass <channel> <on|off>");
        return service.setCrossoverLowPassBypass(args[0], parseOnOff(args[1]));
    }

    public Object firmode(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:firmode <outX> <iir|fir>");
        return service.setFirProcessingMode(args[0], args[1]);
    }

    public Object firgen(String... args) throws Exception {
        requireArgs(args, 6, "Usage: dsp408:firgen <outX> <type> <window> <hpHz> <lpHz> <taps>");
        return service.setFirGenerator(args[0], args[1], args[2], parseDouble(args[3]), parseDouble(args[4]), parseInt(args[5]));
    }

    public Object firset(String... args) throws Exception {
        return firgen(args);
    }

    public Object firupload(String... args) throws Exception {
        requireArgs(args, 3, "Usage: dsp408:firupload <channel> <name> <file|coefficients>");
        return service.executeShell("firupload " + join(args));
    }

    public Object opeqset(String... args) throws Exception {
        requireArgs(args, 6, "Usage: dsp408:opeqset <outX> <band> <type> <hz> <q> <gainDb>");
        return service.setOutputPeq(args[0], parseInt(args[1]), args[2], parseDouble(args[3]), parseDouble(args[4]), parseDouble(args[5]));
    }

    public Object opeqfreq(String... args) throws Exception {
        requireArgs(args, 3, "Usage: dsp408:opeqfreq <outX> <band> <hz>");
        return service.setOutputPeqFrequency(args[0], parseInt(args[1]), parseDouble(args[2]));
    }

    public Object opeqq(String... args) throws Exception {
        requireArgs(args, 3, "Usage: dsp408:opeqq <outX> <band> <q>");
        return service.setOutputPeqQ(args[0], parseInt(args[1]), parseDouble(args[2]));
    }

    public Object opeqqraw(String... args) throws Exception {
        requireArgs(args, 3, "Usage: dsp408:opeqqraw <outX> <band> <0..100>");
        return service.setOutputPeqQRaw(args[0], parseInt(args[1]), parseInt(args[2]));
    }

    public Object opeqgain(String... args) throws Exception {
        requireArgs(args, 3, "Usage: dsp408:opeqgain <outX> <band> <gainDb>");
        return service.setOutputPeqGain(args[0], parseInt(args[1]), parseDouble(args[2]));
    }

    public Object opeqgcode(String... args) throws Exception {
        requireArgs(args, 3, "Usage: dsp408:opeqgcode <outX> <band> <code>");
        return service.setOutputPeqGainCode(args[0], parseInt(args[1]), parseInt(args[2]));
    }

    public Object peqf(String... args) throws Exception {
        return opeqfreq(args);
    }

    public Object peqqraw(String... args) throws Exception {
        return opeqqraw(args);
    }

    public Object peqgcode(String... args) throws Exception {
        return opeqgcode(args);
    }

    public Object opeqtype(String... args) throws Exception {
        requireArgs(args, 3, "Usage: dsp408:opeqtype <outX> <band> <type>");
        return service.setOutputPeqType(args[0], parseInt(args[1]), args[2]);
    }

    public Object ipeqset(String... args) throws Exception {
        requireArgs(args, 7, "Usage: dsp408:ipeqset <inX> <band> <type> <hz> <q> <gainDb> <on|off>");
        return service.setInputPeq(args[0], parseInt(args[1]), args[2], parseDouble(args[3]), parseDouble(args[4]), parseDouble(args[5]), parseOnOff(args[6]));
    }

    public Object ipeqfreq(String... args) throws Exception {
        requireArgs(args, 3, "Usage: dsp408:ipeqfreq <inX> <band> <hz>");
        return service.setInputPeqFrequency(args[0], parseInt(args[1]), parseDouble(args[2]));
    }

    public Object ipeqq(String... args) throws Exception {
        requireArgs(args, 3, "Usage: dsp408:ipeqq <inX> <band> <q>");
        return service.setInputPeqQ(args[0], parseInt(args[1]), parseDouble(args[2]));
    }

    public Object ipeqgain(String... args) throws Exception {
        requireArgs(args, 3, "Usage: dsp408:ipeqgain <inX> <band> <gainDb>");
        return service.setInputPeqGain(args[0], parseInt(args[1]), parseDouble(args[2]));
    }

    public Object ipeqtype(String... args) throws Exception {
        requireArgs(args, 3, "Usage: dsp408:ipeqtype <inX> <band> <type>");
        return service.setInputPeqType(args[0], parseInt(args[1]), args[2]);
    }

    public Object ipeqbypass(String... args) throws Exception {
        requireArgs(args, 3, "Usage: dsp408:ipeqbypass <inX> <band> <on|off>");
        return service.setInputPeqBypass(args[0], parseInt(args[1]), parseOnOff(args[2]));
    }

    public Object igeq(String... args) throws Exception {
        requireArgs(args, 3, "Usage: dsp408:igeq <inX> <band> <gainDb>");
        return service.setInputGeq(args[0], parseInt(args[1]), parseDouble(args[2]));
    }

    public Object gateset(String... args) throws Exception {
        requireArgs(args, 5, "Usage: dsp408:gateset <inX> <thresholdDb> <holdMs> <attackMs> <releaseMs>");
        return service.setInputGate(args[0], parseDouble(args[1]), parseDouble(args[2]), parseDouble(args[3]), parseDouble(args[4]));
    }

    public Object gatethreshold(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:gatethreshold <inX> <thresholdDb>");
        return service.setInputGateThreshold(args[0], parseDouble(args[1]));
    }

    public Object gatehold(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:gatehold <inX> <holdMs>");
        return service.setInputGateHold(args[0], parseDouble(args[1]));
    }

    public Object gateattack(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:gateattack <inX> <attackMs>");
        return service.setInputGateAttack(args[0], parseDouble(args[1]));
    }

    public Object gaterelease(String... args) throws Exception {
        requireArgs(args, 2, "Usage: dsp408:gaterelease <inX> <releaseMs>");
        return service.setInputGateRelease(args[0], parseDouble(args[1]));
    }

    public Object compset(String... args) throws Exception {
        requireArgs(args, 6, "Usage: dsp408:compset <inX> <thresholdDb> <ratio> <attackMs> <releaseMs> <kneeDb>");
        return service.setCompressor(args[0], parseDouble(args[1]), args[2], parseDouble(args[3]), parseDouble(args[4]), parseDouble(args[5]));
    }

    public Object compget(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:compget <inX>");
        return service.executeShell("compget " + args[0]);
    }

    public Object limitset(String... args) throws Exception {
        requireArgs(args, 4, "Usage: dsp408:limitset <outX> <thresholdDb> <attackMs> <releaseMs>");
        return service.setLimiter(args[0], parseDouble(args[1]), parseDouble(args[2]), parseDouble(args[3]));
    }

    public Object limitget(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:limitget <outX>");
        return service.executeShell("limitget " + args[0]);
    }

    public Object tone(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:tone <hz>");
        return service.setTestToneSineFrequency(parseDouble(args[0]));
    }

    public Object toneon(String... args) throws Exception {
        return tone(args);
    }

    public Object tonesource(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:tonesource <analog|pink|white|sine>");
        return service.setTestToneSource(args[0]);
    }

    public Object tonefreq(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:tonefreq <hz>");
        return service.setTestToneSineFrequency(parseDouble(args[0]));
    }

    public Object toneraw(String... args) throws Exception {
        requireArgs(args, 1, "Usage: dsp408:toneraw <0..30>");
        return service.setTestToneSineFrequencyRaw(parseInt(args[0]));
    }

    public Object toneoff() throws Exception {
        return service.disableTestTone();
    }

    public Object volume(String... args) throws Exception {
        if (args == null || args.length == 0) {
            return service.executeVolumeRoom("!dsp volume help");
        }
        return service.executeVolumeRoom("!dsp volume " + join(args));
    }

    public Object volstatus() throws Exception {
        return service.executeVolumeRoom("!dsp volume status");
    }

    public Object volrefresh() throws Exception {
        return service.executeVolumeRoom("!dsp volume refresh");
    }

    public Object volup() throws Exception {
        return service.executeVolumeRoom("!dsp volume up");
    }

    public Object voldown() throws Exception {
        return service.executeVolumeRoom("!dsp volume down");
    }

    public Object volmute() throws Exception {
        return service.executeVolumeRoom("!dsp volume mute");
    }

    public Object volunmute() throws Exception {
        return service.executeVolumeRoom("!dsp volume unmute");
    }

    public Object volset(String value) throws Exception {
        return service.executeVolumeRoom("!dsp volume set " + value);
    }

    public Object volstep(String value) throws Exception {
        return service.executeVolumeRoom("!dsp volume step " + value);
    }

    public Object cmd(String... args) throws Exception {
        if (args == null || args.length == 0) {
            return help();
        }
        return service.executeShell(join(args));
    }

    private Object dispatch(DspService target, String... parts) throws Exception {
        requireArgs(parts, 1, "Usage: dsp408:dsp <dsp-id> <command> [args...]");
        String command = parts[0].trim().toLowerCase();
        String[] args = tail(parts, 1);

        return switch (command) {
            case "help", "h", "?" -> help();
            case "dsps" -> manager.getDspInstances();
            case "current" -> "Selected DSP " + target.dspId();
            case "connect" -> target.connect();
            case "disconnect", "close" -> {
                target.disconnect();
                yield "Disconnected.";
            }
            case "reconnect" -> target.reconnect();
            case "connected" -> target.isConnected() ? "yes" : "no";
            case "state", "status" -> target.executeShell("state");
            case "channels" -> target.getChannels();
            case "get" -> target.getChannel(requiredArg(args, 0, "Usage: dsp408:dsp <id> get <channel>"));
            case "deviceinfo", "info" -> target.getDeviceInfo();
            case "login" -> {
                target.loginPin(requiredArg(args, 0, "Usage: dsp408:dsp <id> login <1234>"));
                yield "Login PIN sent.";
            }
            case "loadpreset" -> loadPreset(target, args);
            case "readpresetname" -> target.readPresetName(parseInt(requiredArg(args, 0, "Usage: dsp408:dsp <id> readpresetname <0..19>")));
            case "meters" -> target.requestRuntimeMeters();
            case "meterstate", "meterscached" -> target.getMeters();
            case "meter" -> target.getMeter(requiredArg(args, 0, "Usage: dsp408:dsp <id> meter <channel>"));
            case "blocks" -> target.getCachedBlockIndices();
            case "readblock" -> target.readBlock(requiredArg(args, 0, "Usage: dsp408:dsp <id> readblock <hex>"));
            case "showblock", "block" -> target.getCachedBlock(requiredArg(args, 0, "Usage: dsp408:dsp <id> showblock <hex>"));
            case "scanblocks", "refresh" -> target.scanBlocks();
            case "raw", "sendraw" -> target.sendRawHex(join(args));
            case "gain" -> target.setGain(
                    requiredArg(args, 0, "Usage: dsp408:dsp <id> gain <channel> <db>"),
                    parseDouble(requiredArg(args, 1, "Usage: dsp408:dsp <id> gain <channel> <db>")));
            case "mute" -> target.mute(requiredArg(args, 0, "Usage: dsp408:dsp <id> mute <channel>"));
            case "unmute" -> target.unmute(requiredArg(args, 0, "Usage: dsp408:dsp <id> unmute <channel>"));
            case "name" -> target.setChannelName(
                    requiredArg(args, 0, "Usage: dsp408:dsp <id> name <channel> <ascii-name>"),
                    requiredArg(args, 1, "Usage: dsp408:dsp <id> name <channel> <ascii-name>"));
            case "phase" -> target.setPhase(
                    requiredArg(args, 0, "Usage: dsp408:dsp <id> phase <channel> <0|180|normal|inverted>"),
                    parsePhase(requiredArg(args, 1, "Usage: dsp408:dsp <id> phase <channel> <0|180|normal|inverted>")));
            case "delay" -> target.setDelay(
                    requiredArg(args, 0, "Usage: dsp408:dsp <id> delay <channel> <ms>"),
                    parseDouble(requiredArg(args, 1, "Usage: dsp408:dsp <id> delay <channel> <ms>")));
            case "delayunit" -> target.setDelayUnit(requiredArg(args, 0, "Usage: dsp408:dsp <id> delayunit <ms|m|ft>"));
            case "route" -> target.setMatrixRoute(
                    requiredArg(args, 0, "Usage: dsp408:dsp <id> route <outX> <inA|inB|inC|inD>"),
                    requiredArg(args, 1, "Usage: dsp408:dsp <id> route <outX> <inA|inB|inC|inD>"));
            case "xgain" -> target.setMatrixCrosspointGain(
                    requiredArg(args, 0, "Usage: dsp408:dsp <id> xgain <outX> <inA|inB|inC|inD> <db>"),
                    requiredArg(args, 1, "Usage: dsp408:dsp <id> xgain <outX> <inA|inB|inC|inD> <db>"),
                    parseDouble(requiredArg(args, 2, "Usage: dsp408:dsp <id> xgain <outX> <inA|inB|inC|inD> <db>")));
            case "firmode" -> target.setFirProcessingMode(
                    requiredArg(args, 0, "Usage: dsp408:dsp <id> firmode <outX> <iir|fir>"),
                    requiredArg(args, 1, "Usage: dsp408:dsp <id> firmode <outX> <iir|fir>"));
            case "firgen", "firset" -> target.setFirGenerator(
                    requiredArg(args, 0, "Usage: dsp408:dsp <id> firgen <outX> <type> <window> <hpHz> <lpHz> <taps>"),
                    requiredArg(args, 1, "Usage: dsp408:dsp <id> firgen <outX> <type> <window> <hpHz> <lpHz> <taps>"),
                    requiredArg(args, 2, "Usage: dsp408:dsp <id> firgen <outX> <type> <window> <hpHz> <lpHz> <taps>"),
                    parseDouble(requiredArg(args, 3, "Usage: dsp408:dsp <id> firgen <outX> <type> <window> <hpHz> <lpHz> <taps>")),
                    parseDouble(requiredArg(args, 4, "Usage: dsp408:dsp <id> firgen <outX> <type> <window> <hpHz> <lpHz> <taps>")),
                    parseInt(requiredArg(args, 5, "Usage: dsp408:dsp <id> firgen <outX> <type> <window> <hpHz> <lpHz> <taps>")));
            case "firupload" -> target.executeShell("firupload " + join(args));
            case "peqf", "opeqfreq" -> target.setOutputPeqFrequency(
                    requiredArg(args, 0, "Usage: dsp408:dsp <id> opeqfreq <outX> <band> <hz>"),
                    parseInt(requiredArg(args, 1, "Usage: dsp408:dsp <id> opeqfreq <outX> <band> <hz>")),
                    parseDouble(requiredArg(args, 2, "Usage: dsp408:dsp <id> opeqfreq <outX> <band> <hz>")));
            case "peqqraw", "opeqqraw" -> target.setOutputPeqQRaw(
                    requiredArg(args, 0, "Usage: dsp408:dsp <id> opeqqraw <outX> <band> <0..100>"),
                    parseInt(requiredArg(args, 1, "Usage: dsp408:dsp <id> opeqqraw <outX> <band> <0..100>")),
                    parseInt(requiredArg(args, 2, "Usage: dsp408:dsp <id> opeqqraw <outX> <band> <0..100>")));
            case "peqgcode", "opeqgcode" -> target.setOutputPeqGainCode(
                    requiredArg(args, 0, "Usage: dsp408:dsp <id> opeqgcode <outX> <band> <code>"),
                    parseInt(requiredArg(args, 1, "Usage: dsp408:dsp <id> opeqgcode <outX> <band> <code>")),
                    parseInt(requiredArg(args, 2, "Usage: dsp408:dsp <id> opeqgcode <outX> <band> <code>")));
            case "tone", "toneon", "tonefreq" -> target.setTestToneSineFrequency(parseDouble(requiredArg(args, 0, "Usage: dsp408:dsp <id> tone <hz>")));
            case "tonesource" -> target.setTestToneSource(requiredArg(args, 0, "Usage: dsp408:dsp <id> tonesource <analog|pink|white|sine>"));
            case "toneraw" -> target.setTestToneSineFrequencyRaw(parseInt(requiredArg(args, 0, "Usage: dsp408:dsp <id> toneraw <0..30>")));
            case "toneoff" -> target.disableTestTone();
            case "volume" -> target.executeVolumeRoom("!dsp volume " + (args.length == 0 ? "help" : join(args)));
            case "volstatus" -> target.executeVolumeRoom("!dsp volume status");
            case "volrefresh" -> target.executeVolumeRoom("!dsp volume refresh");
            case "volup" -> target.executeVolumeRoom("!dsp volume up");
            case "voldown" -> target.executeVolumeRoom("!dsp volume down");
            case "volmute" -> target.executeVolumeRoom("!dsp volume mute");
            case "volunmute" -> target.executeVolumeRoom("!dsp volume unmute");
            case "volset" -> target.executeVolumeRoom("!dsp volume set " + requiredArg(args, 0, "Usage: dsp408:dsp <id> volset <db>"));
            case "volstep" -> target.executeVolumeRoom("!dsp volume step " + requiredArg(args, 0, "Usage: dsp408:dsp <id> volstep <db>"));
            case "cmd" -> target.executeShell(join(args));
            default -> target.executeShell(join(parts));
        };
    }

    private Object loadPreset(DspService target, String[] args) throws Exception {
        String slot = requiredArg(args, 0, "Usage: dsp408:dsp <id> loadpreset <F00|U01..U20|0..20>");
        if (slot.matches("\\d+")) {
            return target.loadPreset(Integer.parseInt(slot));
        }
        return target.loadPreset(slot);
    }

    private Object run(String command) throws Exception {
        return service.executeShell(command);
    }

    private Object runWithArgs(String command, String... args) throws Exception {
        String suffix = join(args);
        return service.executeShell(suffix.isEmpty() ? command : command + " " + suffix);
    }

    private static void requireArgs(String[] args, int count, String usage) {
        if (args == null || args.length < count) {
            throw new IllegalArgumentException(usage);
        }
    }

    private static void requireExactArgs(String[] args, int count, String usage) {
        if (args == null || args.length != count) {
            throw new IllegalArgumentException(usage);
        }
    }

    private static String requiredArg(String[] args, int index, String usage) {
        if (args == null || args.length <= index || args[index] == null || args[index].isBlank()) {
            throw new IllegalArgumentException(usage);
        }
        return args[index].trim();
    }

    private static String[] tail(String[] args, int offset) {
        if (args == null || args.length <= offset) {
            return new String[0];
        }
        String[] out = new String[args.length - offset];
        System.arraycopy(args, offset, out, 0, out.length);
        return out;
    }

    private static int parseInt(String value) {
        return Integer.parseInt(value);
    }

    private static double parseDouble(String value) {
        return Double.parseDouble(value);
    }

    private static boolean parsePhase(String value) {
        return switch (value.trim().toLowerCase()) {
            case "180", "inv", "invert", "inverted" -> true;
            case "0", "normal", "norm" -> false;
            default -> throw new IllegalArgumentException("Phase must be 0|180|normal|inverted");
        };
    }

    private static boolean parseOnOff(String value) {
        return switch (value.trim().toLowerCase()) {
            case "on", "true", "1", "yes" -> true;
            case "off", "false", "0", "no" -> false;
            default -> throw new IllegalArgumentException("Expected on/off");
        };
    }

    private static String join(String... args) {
        StringBuilder sb = new StringBuilder();
        if (args != null) {
            for (String arg : args) {
                if (arg == null) {
                    continue;
                }
                String trimmed = arg.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(trimmed);
            }
        }
        return sb.toString();
    }
}
