package org.example.plugin;

import java.util.Objects;

public record PluginProperty(String key, Object value) {
    public PluginProperty {
        Objects.requireNonNull(key, "key");
    }
}
