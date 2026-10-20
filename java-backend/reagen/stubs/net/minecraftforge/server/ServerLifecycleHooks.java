package net.minecraftforge.server;
/** Стаб: getCurrentServer() возвращает MinecraftServer (Object — чтобы не тянуть тяжёлый класс). */
public class ServerLifecycleHooks {
    public static Object getCurrentServer(){ return null; }
}
