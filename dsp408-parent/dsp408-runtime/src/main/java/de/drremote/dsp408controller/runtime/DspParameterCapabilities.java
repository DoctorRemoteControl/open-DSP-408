package de.drremote.dsp408controller.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import de.drremote.dsp408.api.CapabilitiesDto;
import de.drremote.dsp408.api.ChannelCapabilitiesDto;
import de.drremote.dsp408.api.ParameterCapabilityDto;
import de.drremote.dsp408.api.ParameterStepDto;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.service.DspController;
import de.drremote.dsp408controller.core.service.DspParameter;
import de.drremote.dsp408controller.core.service.DspParameterCapability;
import de.drremote.dsp408controller.core.service.DspParameterCatalog;

/** Maps one immutable core snapshot without bootstrap, refresh or network requests. */
final class DspParameterCapabilities {
    private final DspController controller;
    private final Supplier<String> idSupplier;

    DspParameterCapabilities(DspController controller, Supplier<String> idSupplier) {
        this.controller = Objects.requireNonNull(controller);
        this.idSupplier = Objects.requireNonNull(idSupplier);
    }

    CapabilitiesDto get() {
        DspParameterCatalog catalog = controller.parameterCatalog();
        List<String> names = Arrays.stream(DspParameter.values()).map(DspParameter::id).toList();
        List<ChannelCapabilitiesDto> channels = Arrays.stream(DspChannel.values())
                .map(channel -> new ChannelCapabilitiesDto(DspRuntimeDtoMapper.channelId(channel), channel.displayName(),
                        channel.index(), Arrays.stream(DspParameter.values())
                                .map(parameter -> map(catalog.capability(channel, parameter))).toList()))
                .toList();
        String reason = !catalog.connected() ? "DSP is disconnected"
                : !catalog.firmwareRecognized() ? "Firmware identity and library are not a recognized profile" : null;
        return new CapabilitiesDto(idSupplier.get(), catalog.connected(), catalog.firmwareRecognized(),
                catalog.deviceVersion(), catalog.libraryType(), names, channels, reason);
    }

    private static ParameterCapabilityDto map(DspParameterCapability capability) {
        return new ParameterCapabilityDto(capability.parameter().id(), capability.valueType(), capability.unit(),
                capability.minimum(), capability.maximum(), capability.resolution().stream()
                        .map(step -> new ParameterStepDto(step.fromInclusive(), step.toInclusive(), step.step())).toList(),
                capability.writable(), capability.readbackSupported(), capability.writeEvidence(), capability.readEvidence(), capability.reason());
    }
}
