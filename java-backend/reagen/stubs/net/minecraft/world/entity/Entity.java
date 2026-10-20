package net.minecraft.world.entity;
import java.util.UUID;
public abstract class Entity {
    public double getX(){return 0;} public double getY(){return 0;} public double getZ(){return 0;}
    public UUID getUUID(){return null;}
    public void setPos(double x,double y,double z){}
    public float getHealth(){return 20f;}
    public boolean isRemoved(){return false;}
}
