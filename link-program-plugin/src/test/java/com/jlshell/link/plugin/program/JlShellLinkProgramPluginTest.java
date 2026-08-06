package com.jlshell.link.plugin.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import com.google.gson.JsonNull;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.plugin.api.JlShellProgramPlugin;
import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.ProgramPluginContext;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilityRegistry;
import com.jlshell.plugin.api.rpc.CapabilitySpec;
import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.plugin.api.connection.ProgramConnectionIntegration;
import com.jlshell.plugin.api.connection.ProgramConnectionRouteContribution;
import com.jlshell.plugin.api.storage.PluginStorage;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import org.junit.jupiter.api.Test;

class JlShellLinkProgramPluginTest {

    @Test
    void serviceLoaderDiscoversProgramPlugin() {
        assertThat(ServiceLoader.load(JlShellProgramPlugin.class).stream()
                .map(provider -> provider.get().id()))
                .contains(LinkPluginContract.PROGRAM_PLUGIN_ID);
    }

    @Test
    void settingsDependenciesAreAbsentBeforeActivation() {
        assertThat(new JlShellLinkProgramPlugin().settingsDependenciesReady()).isFalse();
    }

    @Test
    void activationRegistersStatusAndDeactivationRemovesIt() throws Exception {
        TestRegistry registry = new TestRegistry();
        TestProgramContext context = new TestProgramContext(registry);
        JlShellLinkProgramPlugin plugin = new JlShellLinkProgramPlugin();

        plugin.activate(context);

        Capability capability = registry.resolve(LinkPluginContract.RUNTIME_STATUS_CAPABILITY).orElseThrow();
        var result = capability.handler().invoke(JsonNull.INSTANCE, null).join().getAsJsonObject();
        assertThat(result.get("available").getAsBoolean()).isFalse();
        assertThat(result.get("state").getAsString())
                .isIn("RUNTIME_MISSING", "CONNECTOR_NOT_READY", "SIGNED_OUT");
        assertThat(result.has("version")).isTrue();
        assertThat(result.getAsJsonObject("runtime").get("state").getAsString())
                .as(result.getAsJsonObject("runtime").toString())
                .isIn("BUNDLE_MISSING", "READY");
        assertThat(context.connectionIntegration.contribution).isNotNull();

        plugin.deactivate();
        assertThat(registry.resolve(LinkPluginContract.RUNTIME_STATUS_CAPABILITY)).isEmpty();
    }

    private static final class TestRegistry implements CapabilityRegistry {
        private final Map<String, Capability> capabilities = new LinkedHashMap<>();

        @Override public void register(Capability capability) {
            capabilities.put(capability.spec().name(), capability);
        }

        @Override public void unregister(String name) {
            capabilities.remove(name);
        }

        @Override public List<CapabilitySpec> specs() {
            return capabilities.values().stream().map(Capability::spec).toList();
        }

        @Override public Optional<Capability> resolve(String name) {
            return Optional.ofNullable(capabilities.get(name));
        }
    }

    private static final class TestProgramContext implements ProgramPluginContext {
        private final TestRegistry capabilityRegistry;
        private final TestConnectionIntegration connectionIntegration = new TestConnectionIntegration();

        private TestProgramContext(TestRegistry capabilityRegistry) {
            this.capabilityRegistry = capabilityRegistry;
        }

        @Override public TestRegistry capabilityRegistry() { return capabilityRegistry; }
        @Override public String themeName() { return "dark"; }
        @Override public ReadOnlyStringProperty themeNameProperty() {
            return new SimpleStringProperty("dark");
        }
        @Override public Locale locale() { return Locale.SIMPLIFIED_CHINESE; }
        @Override public ReadOnlyObjectProperty<Locale> localeProperty() {
            return new SimpleObjectProperty<>(locale());
        }
        @Override public CapabilityBus capabilityBus() { return null; }
        @Override public PluginStorage storage() { return null; }
        @Override public ProgramConnectionIntegration connectionIntegration() { return connectionIntegration; }
        @Override public String resolveI18n(String key, String fallback) { return fallback; }
        @Override public void showNotification(String message, NotificationLevel level) { }
    }

    private static final class TestConnectionIntegration implements ProgramConnectionIntegration {
        private ProgramConnectionRouteContribution contribution;
        @Override public boolean available() { return true; }
        @Override public Registration register(ProgramConnectionRouteContribution contribution) {
            this.contribution = contribution;
            return () -> this.contribution = null;
        }
    }
}
