package net.minecraft.world.entity;
import net.minecraft.world.level.Level;
import java.util.Optional;
public abstract class EntityType<T extends Entity> {
    public abstract T create(Level level);
    public static Optional<EntityType<?>> byString(String id){ return Optional.empty(); }
}
