package exn.database.dualwield;

import net.minecraft.client.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.renderer.FirstPersonRenderer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.PlayerModelPart;
import net.minecraft.item.Item;
import net.minecraft.item.TieredItem;
import net.minecraft.item.TridentItem;
import net.minecraft.network.play.client.CClientSettingsPacket;
import net.minecraft.network.play.client.CPlayerDiggingPacket;
import net.minecraft.util.Direction;
import net.minecraft.util.HandSide;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

@Mod(DualWield.MODID)
public class DualWield {
	public static final String MODID = "dualwield";
	private static final boolean IS_DEV_ENVIRONMENT = System.getProperty("dual_wield.dev") != null; //Use vm arg "-Ddual_wield.dev" to enable

	//Cache reflected methods and fields
	private final Method clickMouse = getMethod(Minecraft.class, "clickMouse", "func_147116_af");
	private final Field setModelParts = getField(GameSettings.class, "setModelParts", "field_178882_aU"),
						equippedProgressMainHand = getField(FirstPersonRenderer.class, "equippedProgressMainHand", "field_187469_f"),
						equippedProgressOffHand = getField(FirstPersonRenderer.class, "equippedProgressOffHand", "field_187471_h");

	private HandSide initialPrimaryHand;
	private boolean dualWielding;

	public DualWield() {
		MinecraftForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if(event.phase == TickEvent.Phase.START) {
			ClientPlayerEntity player = Minecraft.getInstance().player;

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
		if(event.getPlayer().isUser()) //Get the primary hand for the first time when logging in
			initialPrimaryHand = event.getPlayer().getPrimaryHand();
	}

	@SubscribeEvent
	public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		if(event.getPlayer().isUser()) //Set the primary hand to the last user-set value
			setHand(initialPrimaryHand);
	}

	@SubscribeEvent
	public void onMouseInput(InputEvent.MouseInputEvent event) {
		if(dualWielding) {
			Minecraft mc = Minecraft.getInstance();
			GameSettings settings = mc.gameSettings;

			boolean keyAttackPressed = settings.keyBindAttack.isPressed();
			boolean keyUsePressed = settings.keyBindUseItem.isPressed();

			//Override left and right click behaviour while dual wielding
			if(keyAttackPressed || keyUsePressed) {
				KeyBinding keyClick = keyAttackPressed ? settings.keyBindAttack : settings.keyBindAttack;

				//Cancel the mouse click
				keyClick.isPressed();
				KeyBinding.setKeyBindState(keyClick.getKey(), false);

				//Set hand associated with mouse button
				setHand(keyAttackPressed ? HandSide.LEFT : HandSide.RIGHT);

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
		return item instanceof TieredItem || item instanceof TridentItem;
	}

	/**
	 * Set the primary hand while keeping items in the original hand
	 * @param hand Hand to make primary
	 */
	private void setHand(HandSide hand) {
		Minecraft mc = Minecraft.getInstance();
		ClientPlayerEntity player = mc.player;

		if(player.getPrimaryHand() != hand) {
			GameSettings settings = mc.gameSettings;

			try {
				int i = 0;

				//Taken from net.minecraft.client.settings.GameSettings#sendSettingsToServer
				for(PlayerModelPart parts : (Set<PlayerModelPart>)setModelParts.get(settings))
					i |= parts.getPartMask();

				//Keep old client settings, but with new hand preference
				player.connection.sendPacket(new CClientSettingsPacket(settings.language, settings.renderDistanceChunks, settings.chatVisibility, settings.chatColor, i, hand));

				//Swap the items to put them back in their original hands
				mc.getConnection().sendPacket(new CPlayerDiggingPacket(CPlayerDiggingPacket.Action.SWAP_HELD_ITEMS, BlockPos.ZERO, Direction.DOWN));

				//Disable re-equip animation
				FirstPersonRenderer firstPersonRenderer = mc.getFirstPersonRenderer();
				equippedProgressMainHand.set(firstPersonRenderer, 1f);
				equippedProgressOffHand.set(firstPersonRenderer, 1f);
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
