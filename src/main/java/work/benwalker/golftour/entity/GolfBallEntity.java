package work.benwalker.golftour.entity;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import work.benwalker.golftour.registry.GolfItems;

/**
 * The golf ball. It has no physics of its own: the server simulates each shot up front and moves the ball
 * along the recorded path one tick at a time. Its position is the ball's centre.
 */
public class GolfBallEntity extends Entity implements ItemSupplier {
	/** Visual radius; the physics works with the contact point {@link #RADIUS} below the entity position. */
	public static final double RADIUS = 0.1;

	public GolfBallEntity(EntityType<? extends GolfBallEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder entityData) {
	}

	@Override
	public void tick() {
		// Moved externally by the round manager.
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 300 * 300;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}

	@Override
	public ItemStack getItem() {
		return new ItemStack(GolfItems.GOLF_BALL);
	}
}
