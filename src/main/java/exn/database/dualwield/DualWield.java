package exn.database.dualwield;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.item.*;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.network.play.client.CPacketClientSettings;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumHandSide;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

@Mod(modid = DualWield.MODID)
public class DualWield {
	public static final String MODID = "dualwield";
	private static final boolean IS_DEV_ENVIRONMENT = (Boolean)Launch.blackboard.get("fml.deobfuscatedEnvironment");

	//Cache reflected methods and fields
	private final Method clickMouse = getMethod(Minecraft.class, "clickMouse", "func_147116_af");
	private final Field setModelParts = getField(GameSettings.class, "setModelParts", "field_178882_aU"),
						equippedProgressMainHand = getField(ItemRenderer.class, "equippedProgressMainHand", "field_187469_f"),
						equippedProgressOffHand = getField(ItemRenderer.class, "equippedProgressOffHand", "field_187471_h");

	private EnumHandSide initialPrimaryHand;
	private boolean dualWielding;

	@Mod.EventHandler
	public void onInitialization(FMLInitializationEvent event) {
		MinecraftForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if(event.phase == TickEvent.Phase.START) {
			EntityPlayer player = Minecraft.getMinecraft().player;

			if(player != null) {
				//Detect if the players main and offhand items can be dual wielded
				boolean canDualWield = canDualWield(player.getHeldItemMainhand().getItem()) && canDualWield(player.getHeldItemOffhand().getItem());

				if(canDualWield && !dualWielding) //If the player starts dual wielding, remember their primary hand
					initialPrimaryHand = player.getPrimaryHand();
				else if(!canDualWield && dualWielding) //If the player stops dual wielding, set the primary hand back to the remembered one
					setHand(initialPrimaryHand);

				dualWielding = canDualWield;
			}
		}
	}

	@SubscribeEvent
	public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if(event.player.isUser()) //Get the primary hand for the first time when logging in
			initialPrimaryHand = event.player.getPrimaryHand();
	}

	@SubscribeEvent
	public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		if(event.player.isUser()) //Set the primary hand to the last user-set value
			setHand(initialPrimaryHand);
	}

	@SubscribeEvent
	public void onMouseInput(InputEvent.MouseInputEvent event) {
		if(dualWielding) {
			Minecraft mc = Minecraft.getMinecraft();
			GameSettings settings = mc.gameSettings;

			boolean keyAttackPressed = settings.keyBindAttack.isPressed();
			boolean keyUsePressed = settings.keyBindUseItem.isPressed();

			//Override left and right click behaviour while dual wielding
			if(keyAttackPressed || keyUsePressed) {
				KeyBinding keyClick = keyAttackPressed ? settings.keyBindAttack : settings.keyBindAttack;

				//Cancel the mouse click
				keyClick.isPressed();
				KeyBinding.setKeyBindState(keyClick.getKeyCode(), false);

				//Set hand associated with mouse button
				setHand(keyAttackPressed ? EnumHandSide.LEFT : EnumHandSide.RIGHT);

				try {
					clickMouse.invoke(mc);
				} catch(IllegalAccessException | InvocationTargetException e) {
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * Checks if an item can be dual wielded best on its inheritance
	 * @param item The item to check
	 * @return Whether the item can be dual wielded
	 */
	private boolean canDualWield(Item item) {
		return item instanceof ItemSword || item instanceof ItemTool || item instanceof ItemHoe;
	}

	/**
	 * Set the primary hand while keeping items in the original hand
	 * @param hand Hand to make primary
	 */
	private void setHand(EnumHandSide hand) {
		Minecraft mc = Minecraft.getMinecraft();
		EntityPlayerSP player = mc.player;

		if(player.getPrimaryHand() != hand) {
			GameSettings settings = mc.gameSettings;

			try {
				int i = 0;

				//Taken from net.minecraft.client.settings.GameSettings#sendSettingsToServer
				for(EnumPlayerModelParts parts : (Set<EnumPlayerModelParts>)setModelParts.get(settings))
					i |= parts.getPartMask();

				//Keep old client settings, but with new hand preference
				player.connection.sendPacket(new CPacketClientSettings(settings.language, settings.renderDistanceChunks, settings.chatVisibility, settings.chatColours, i, hand));

				//Swap the items to put them back in their original hands
				mc.getConnection().sendPacket(new CPacketPlayerDigging(CPacketPlayerDigging.Action.SWAP_HELD_ITEMS, BlockPos.ORIGIN, EnumFacing.DOWN));

				//Disable re-equip animation
				ItemRenderer itemRenderer = mc.entityRenderer.itemRenderer;
				equippedProgressMainHand.set(itemRenderer, 1f);
				equippedProgressOffHand.set(itemRenderer, 1f);
			} catch(IllegalAccessException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * @param cl Class to find method in
	 * @param sourceName Name for the method in a deobfuscated environment
	 * @param obfuscatedName Name for the method in a obfuscated environment
	 * @param parameters Parameters to match the method with
	 * @param <T> Type of class to find method in
	 * @return The specified method
	 */
	private static <T> Method getMethod(Class<T> cl, String sourceName, String obfuscatedName, Class<?> ...parameters) {
		Method method;

		try {
			method = cl.getDeclaredMethod(IS_DEV_ENVIRONMENT ? sourceName : obfuscatedName, parameters);
			method.setAccessible(true);
		} catch(NoSuchMethodException e) {
			method = null;
		}

		return method;
	}

	/**
	 * @param cl Class to find field in
	 * @param sourceName Name for the field in a deobfuscated environment
	 * @param obfuscatedName Name for the field in a obfuscated environment
	 * @param <T> Type of class to find field in
	 * @return The specified field
	 */
	private static <T> Field getField(Class<T> cl, String sourceName, String obfuscatedName) {
		Field field;

		try {
			field = cl.getDeclaredField(IS_DEV_ENVIRONMENT ? sourceName : obfuscatedName);
			field.setAccessible(true);
		} catch(NoSuchFieldException e) {
			field = null;
		}

		return field;
	}
}
