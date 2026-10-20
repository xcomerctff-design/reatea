package net.minecraft.world.entity.player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
public abstract class Player extends Entity {
    public Inventory getInventory(){return new Inventory();}
    public static class Inventory { public void add(ItemStack s){} public ItemStack getItem(int i){return new ItemStack();} }
}
