package net.minecraft.world.entity.player;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
public abstract class Player extends Entity {
    public Inventory getInventory(){return new Inventory();}
    public void addEffect(MobEffectInstance inst){}
    public float getHealth(){return 20f;}
    public void setHealth(float h){}
    /** Стаб: в реальном API проверяется Level.getGameType() == SURVIVAL. */
    public boolean isSurvival(){return true;}
    public static class Inventory { public void add(ItemStack s){} public ItemStack getItem(int i){return new ItemStack();} }
}
