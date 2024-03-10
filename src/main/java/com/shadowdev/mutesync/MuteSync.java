package com.shadowdev.mutesync;

import java.io.File;
import java.io.InputStream;
import java.util.UUID;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import org.apache.commons.io.FileUtils;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.dependencies.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import github.scarsz.discordsrv.dependencies.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import github.scarsz.discordsrv.dependencies.jda.api.hooks.ListenerAdapter;
import github.scarsz.discordsrv.util.DiscordUtil;
import space.arim.libertybans.api.LibertyBans;
import space.arim.libertybans.api.PlayerVictim;
import space.arim.libertybans.api.PunishmentType;
import space.arim.libertybans.api.Victim;
import space.arim.libertybans.api.Victim.VictimType;
import space.arim.libertybans.api.event.PardonEvent;
import space.arim.libertybans.api.event.PunishEvent;
import space.arim.libertybans.api.punish.DraftPunishment;
import space.arim.libertybans.api.punish.PunishmentDrafter;
import space.arim.libertybans.api.punish.PunishmentRevoker;
import space.arim.libertybans.api.punish.RevocationOrder;
import space.arim.omnibus.Omnibus;
import space.arim.omnibus.OmnibusProvider;
import space.arim.omnibus.events.EventConsumer;
import space.arim.omnibus.events.ListenerPriorities;

public final class MuteSync extends JavaPlugin {
	final Logger logger = this.getLogger();

	final int currentConfig = 1;

	Omnibus omnibus = OmnibusProvider.getOmnibus();
	LibertyBans libertyBans = omnibus.getRegistry().getProvider(LibertyBans.class)
			.orElseThrow();

	String muteRole;

	@Override
	public void onEnable() {
		Boolean configIsValid = checkConfig();
		if (configIsValid) {
			getConfig().options().copyDefaults();
			saveDefaultConfig();

			muteRole = getConfig().getString("mute-role");

			// ======== LibertyBans Listener ========

			if (getConfig().getBoolean("minecraft-to-discord-mute")) {

				// Mute is added
				EventConsumer<PunishEvent> listener = new EventConsumer<>() {
					@Override
					public void accept(PunishEvent event) {
						DraftPunishment punishment = event.getDraftSanction();
						if (punishment.getType() == PunishmentType.MUTE) {
							Victim victim = punishment.getVictim();
							if (victim.getType() == VictimType.PLAYER) {
								UUID uuid = ((PlayerVictim) victim).getUUID();
								String discordId = DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(uuid);
								if (discordId != null) {
									debug(String.format("Mute enacted on %s (%s)", discordId, uuid));
									DiscordUtil.getJda().getGuildById(getConfig().getString("guild-id"))
											.addRoleToMember(discordId, DiscordUtil.getJda().getRoleById(muteRole))
											.queue();
								}

							}
						}

					}
				};
				OmnibusProvider.getOmnibus().getEventBus().registerListener(PunishEvent.class,
						ListenerPriorities.NORMAL,
						listener);

			}

			// Mute is removed

			if (getConfig().getBoolean("minecraft-to-discord-unmute")) {

				EventConsumer<PardonEvent> pardonListener = new EventConsumer<>() {
					@Override
					public void accept(PardonEvent event) {
						if (event.getPunishmentType() == PunishmentType.MUTE) {
							Victim victim = event.getPardonedVictim();
							if (victim.getType() == VictimType.PLAYER) {
								UUID uuid = ((PlayerVictim) victim).getUUID();
								String discordId = DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(uuid);
								if (discordId != null) {
									debug(String.format("Mute revoked on %s (%s)", discordId, uuid));
									DiscordUtil.getJda().getGuildById(getConfig().getString("guild-id"))
											.removeRoleFromMember(discordId, DiscordUtil.getJda().getRoleById(muteRole))
											.queue();
								}
							}
						}
					}
				};
				OmnibusProvider.getOmnibus().getEventBus().registerListener(PardonEvent.class,
						ListenerPriorities.NORMAL,
						pardonListener);

			}

			// ======== DiscordSRV Listener ========

			if (DiscordUtil.getJda() != null) {
				DiscordUtil.getJda().addEventListener(new ListenerAdapter() {

					// Mute role added
					@Override
					public void onGuildMemberRoleAdd(@Nonnull GuildMemberRoleAddEvent event) {
						if (event.getMember().getUser().isBot())
							return;
						if (getConfig().getBoolean("discord-to-minecraft-mute")) {
							if (event.getRoles().stream().anyMatch(r -> r.getId().equals(muteRole))) {
								debug(String.format("Mute role added to %s (%s)", event.getMember().getId(),
										event.getMember().getEffectiveName()));
								muteUser(event.getMember().getId());
							}
						}
					}

					// Mute role removed
					@Override
					public void onGuildMemberRoleRemove(@Nonnull GuildMemberRoleRemoveEvent event) {
						if (event.getMember().getUser().isBot())
							return;
						if (getConfig().getBoolean("discord-to-minecraft-unmute")) {
							if (event.getRoles().stream().anyMatch(r -> r.getId().equals(muteRole))) {
								debug(String.format("Mute role removed from %s (%s)", event.getMember().getId(),
										event.getMember().getEffectiveName()));
								unMuteUser(event.getMember().getId());
							}
						}
					}
				});
			}

		}

	}

	void muteUser(String discordUserId) {
		UUID uuid = getMinecraftPlayer(discordUserId);
		PunishmentDrafter drafter = libertyBans.getDrafter();
		DraftPunishment draftBan = drafter
				.draftBuilder()
				.type(PunishmentType.BAN)
				.victim(PlayerVictim.of(uuid))
				.reason(getConfig().getString("mute-reason"))
				.build();

		draftBan.enactPunishment().thenAcceptSync((punishment) -> {
			punishment.ifPresentOrElse(p -> {
				debug(String.format("ID of the enacted punishment is %s", p.getIdentifier()));
			}, () -> {
				debug(String.format("UUID %s is already muted", uuid));
			});
		});
	}

	void unMuteUser(String discordUserId) {
		UUID uuid = getMinecraftPlayer(discordUserId);
		PunishmentRevoker revoker = libertyBans.getRevoker();

		RevocationOrder revocationOrder = revoker.revokeByTypeAndVictim(
				PunishmentType.MUTE, PlayerVictim.of(uuid));
		revocationOrder.undoPunishment().thenAccept((undone) -> {
			if (undone) {
				debug(discordUserId + " has been unmuted.");
			} else {
				debug(discordUserId + " is not muted.");
			}
		});
	}

	UUID getMinecraftPlayer(String discordUserId) {
		return DiscordSRV.getPlugin().getAccountLinkManager().getUuid(discordUserId);
	}

	@Override
	public void onDisable() {
		logger.info("MuteSync has been disabled.");
		Bukkit.getScheduler().cancelTasks(this);
	}

	Boolean checkConfig() {
		int configVersion = this.getConfig().getInt("config-version");
		if (configVersion != this.currentConfig) {
			File oldConfigTo = new File(this.getDataFolder(), "config-old-" + configVersion + ".yml");
			File old = new File(this.getDataFolder(), "config.yml");
			try {
				FileUtils.moveFile(old, oldConfigTo);
				getConfig().options().copyDefaults();
				saveDefaultConfig();
				this.logger.severe("Your config is outdated. Your old config has been moved to " + oldConfigTo.getName()
						+ ", and the new version has been applied in its place.");
			} catch (Exception e) {
				File newConfig = new File(this.getDataFolder(), "config-new.yml");
				InputStream newConfigData = this.getResource("config.yml");
				try {
					FileUtils.copyInputStreamToFile(newConfigData, newConfig);
					this.logger.severe(
							"Your config is outdated, but I was unable to replace your old config. Instead, the new config has been saved to "
									+ newConfig.getName() + ".");
				} catch (Exception e1) {
					this.logger.severe(
							"Your config is outdated, but I could not move your old config to a backup or copy in the new config format.");
				}

			}

			this.logger.severe(
					"The plugin will now disable, please migrate the values from your old config to the new one.");
			this.getServer().getPluginManager().disablePlugin(this);
			return false;
		} else {
			File newConfig = new File(this.getDataFolder(), "config-new.yml");
			if (newConfig.exists())
				FileUtils.deleteQuietly(newConfig);
		}
		return true;
	}

	public void debug(String message) {
		if (this.getConfig().getBoolean("debug")) {
			this.logger.info(message);
		}
	}

}