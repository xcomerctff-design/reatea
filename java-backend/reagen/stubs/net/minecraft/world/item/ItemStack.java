package net.minecraft.world.item;
public class ItemStack {
    public static final ItemStack EMPTY = new ItemStack();
    public int getCount(){return 0;}
    public ItemStack(){}
    public ItemStack(ItemStack other){}
    public ItemStack copy(){return new ItemStack(this);}
    public boolean isEmpty(){return getCount()==0;}
}
