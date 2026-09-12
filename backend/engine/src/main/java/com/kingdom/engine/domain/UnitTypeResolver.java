package com.kingdom.engine.domain;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Resolves shop/CLI/JSON unit type names to UnitDefinition.
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
                "Unknown unit type: \"" + type + "\". "
                    + "Valid types: " + FACTORIES.keySet());
        }
        return factory.get();
    }

    /** True if type is one of the six recruitable unit names. */
    public static boolean isKnownType(String type) {
        return type != null && FACTORIES.containsKey(type);
    }
}
