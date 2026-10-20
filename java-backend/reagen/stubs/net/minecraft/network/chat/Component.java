package net.minecraft.network.chat;
/** Стаб реального API 1.20.1: Component.literal(String) и overload с isStyleSuppressed. */
public interface Component {
    static Component literal(String s){ return new Component(){}; }
    static Component literal(String s, boolean filterStyle){ return new Component(){}; }
}
