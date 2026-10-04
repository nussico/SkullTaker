package io.github.nussico.skulltaker;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SkullTaker implements ModInitializer {
	static class Stats { String name; int kills; int deaths; }

	private static final Gson GSON = new Gson();
	// keyed by player UUID string; ponytail: whole map rewritten on every kill, fine until thousands of players
	private static Map<String, Stats> stats = new HashMap<>();
	private static Path file;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(SkullTaker::load);

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof ServerPlayer victim) || !(source.getEntity() instanceof ServerPlayer killer) || killer == victim) return;

			ItemStack head = new ItemStack(Items.PLAYER_HEAD);
			head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(victim.getGameProfile()));
			head.set(DataComponents.LORE, new ItemLore(List.of(
					Component.literal("Killed by " + killer.getName().getString()).withStyle(ChatFormatting.RED),
					Component.literal(LocalDate.now().toString()).withStyle(ChatFormatting.GRAY))));
			victim.spawnAtLocation(victim.level(), head);

			record(killer).kills++;
			record(victim).deaths++;
			save();
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
				Commands.literal("skulltaker")
						.executes(ctx -> top(ctx.getSource()))
						.then(Commands.literal("reset")
								.requires(src -> src.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
								.executes(ctx -> reset(ctx.getSource(), null))
								.then(Commands.argument("player", StringArgumentType.word())
										.executes(ctx -> reset(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
						.then(Commands.argument("player", StringArgumentType.word())
								.executes(ctx -> lookup(ctx.getSource(), StringArgumentType.getString(ctx, "player"))))));
	}

	private static int top(CommandSourceStack src) {
		List<Map.Entry<String, Stats>> top = stats.entrySet().stream()
				.sorted(Comparator.comparingInt((Map.Entry<String, Stats> e) -> e.getValue().kills).reversed()).toList();
		if (top.isEmpty()) {
			src.sendSystemMessage(Component.literal("No PvP kills yet."));
			return 0;
		}
		if (src.getPlayer() != null) {
			List<Map.Entry<String, Stats>> all = top;
			SimpleContainer chest = new SimpleContainer(54);
			fillPage(chest, all, 0);
			src.getPlayer().openMenu(new SimpleMenuProvider((id, inv, p) -> new ChestMenu(MenuType.GENERIC_9x6, id, inv, chest, 6) {
				int page = 0;

				// read-only: only the arrows do anything; always resync so the client doesn't show a phantom item
				@Override public void clicked(int slot, int button, ContainerInput input, Player player) {
					if (slot == PREV_SLOT && page > 0) fillPage(chest, all, --page);
					else if (slot == NEXT_SLOT && (page + 1) * PER_PAGE < all.size()) fillPage(chest, all, ++page);
					sendAllDataToRemote();
				}
				@Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
			}, Component.literal("SkullTaker Leaderboard")));
			return top.size();
		}
		top = top.subList(0, Math.min(10, top.size()));
		src.sendSystemMessage(Component.literal("Top killers:").withStyle(ChatFormatting.GOLD));
		for (int i = 0; i < top.size(); i++) {
			Stats s = top.get(i).getValue();
			src.sendSystemMessage(Component.literal((i + 1) + ". " + s.name + " - " + s.kills + " kills, " + s.deaths + " deaths"));
		}
		return top.size();
	}

	private static final int PER_PAGE = 45, PREV_SLOT = 45, NEXT_SLOT = 53;

	// top 5 rows: heads for this page; bottom row: arrows when there is a previous/next page
	private static void fillPage(SimpleContainer chest, List<Map.Entry<String, Stats>> all, int page) {
		chest.clearContent();
		for (int i = 0; i < PER_PAGE && page * PER_PAGE + i < all.size(); i++) {
			int rank = page * PER_PAGE + i;
			Stats s = all.get(rank).getValue();
			ItemStack head = new ItemStack(Items.PLAYER_HEAD);
			head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(UUID.fromString(all.get(rank).getKey())));
			head.set(DataComponents.CUSTOM_NAME, Component.literal("#" + (rank + 1) + " " + s.name).withStyle(st -> st.withItalic(false).withColor(ChatFormatting.GOLD)));
			head.set(DataComponents.LORE, new ItemLore(List.of(
					Component.literal("Kills: " + s.kills).withStyle(ChatFormatting.GREEN),
					Component.literal("Deaths: " + s.deaths).withStyle(ChatFormatting.RED))));
			chest.setItem(i, head);
		}
		int pages = (all.size() + PER_PAGE - 1) / PER_PAGE;
		if (page > 0) chest.setItem(PREV_SLOT, button("Previous page", page, pages));
		if (page + 1 < pages) chest.setItem(NEXT_SLOT, button("Next page", page + 2, pages));
	}

	private static ItemStack button(String label, int targetPage, int pages) {
		ItemStack arrow = new ItemStack(Items.ARROW);
		arrow.set(DataComponents.CUSTOM_NAME, Component.literal(label + " (" + targetPage + "/" + pages + ")").withStyle(st -> st.withItalic(false).withColor(ChatFormatting.YELLOW)));
		return arrow;
	}

	private static int reset(CommandSourceStack src, String name) {
		if (name == null) {
			int n = stats.size();
			stats.clear();
			save();
			src.sendSuccess(() -> Component.literal("Reset PvP stats for " + n + " players"), true);
			return n;
		}
		if (!stats.values().removeIf(s -> s.name.equalsIgnoreCase(name))) {
			src.sendFailure(Component.literal("No PvP record for " + name));
			return 0;
		}
		save();
		src.sendSuccess(() -> Component.literal("Reset PvP stats for " + name), true);
		return 1;
	}

	private static int lookup(CommandSourceStack src, String name) {
		Stats s = stats.values().stream().filter(x -> x.name.equalsIgnoreCase(name)).findFirst().orElse(null);
		if (s == null) {
			src.sendFailure(Component.literal("No PvP record for " + name));
			return 0;
		}
		src.sendSystemMessage(Component.literal(s.name + ": " + s.kills + " kills, " + s.deaths + " deaths"));
		return s.kills;
	}

	private static Stats record(ServerPlayer p) {
		Stats s = stats.computeIfAbsent(p.getStringUUID(), k -> new Stats());
		s.name = p.getName().getString(); // keep latest name after renames
		return s;
	}

	private static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("skulltaker.json");
		try {
			stats = Files.exists(file) ? GSON.fromJson(Files.readString(file), new TypeToken<Map<String, Stats>>() {}.getType()) : new HashMap<>();
		} catch (IOException e) {
			throw new RuntimeException("Failed to read " + file, e); // don't silently start empty and overwrite the file
		}
	}

	private static void save() {
		try {
			Files.writeString(file, GSON.toJson(stats));
		} catch (IOException e) {
			throw new RuntimeException("Failed to write " + file, e);
		}
	}
}
