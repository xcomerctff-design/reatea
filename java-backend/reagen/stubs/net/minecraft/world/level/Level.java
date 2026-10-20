package net.minecraft.world.level;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
public interface Level {
    boolean addFreshEntity(Entity e);
    void playSound(Player except, double x, double y, double z, SoundEvent sound, SoundSource category, float volume, float pitch);
}
