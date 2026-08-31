package com.kingdom.engine.cli;

import java.util.Map;
import java.util.function.Supplier;

import com.kingdom.engine.domain.UnitDefinition;

/**
 * Translates the CLI/JSON-facing unit type name (e.g. "Squire") into a domain. 
 */
public final class UnitTypeResolver {

    private static final Map<String, Supplier<UnitDefinition>> FACTORIES = Map.of(
            "Squire", UnitDefinition::squire,
            "Shieldbearer", UnitDefinition::shieldbearer,
            "Ranger", UnitDefinition::ranger,
            "Knight", UnitDefinition::knight,
            "Mage", UnitDefinition::mage,
            "Healer", UnitDefinition::healer
    );

    private UnitTypeResolver() {
        // static utility, not instantiable
    }

    public static UnitDefinition resolve(String type) {
        Supplier<UnitDefinition> factory = FACTORIES.get(type);
        if (factory == null) {
            throw new IllegalArgumentException(
                "Unknown unit type in replay file: \"" + type + "\". "
                + "Valid types: " + FACTORIES.keySet());
        }
        return factory.get();
    }
}