package dev.qan4ur.faketab.paper.packet;

import com.mojang.authlib.GameProfile;
import dev.qan4ur.faketab.paper.core.FakeEntry;
import io.papermc.paper.adventure.PaperAdventure;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.world.level.GameType;
import org.bukkit.GameMode;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Единственное место, которое работает с NMS (Mojang-маппинги, Paper 1.21.7).
 * Вызывать только из главного потока.
 *
 * <p>Сигнатура Entry в 1.21.7 (проверено javap по dev bundle):
 * {@code Entry(UUID, GameProfile, boolean listed, int latency, GameType, Component displayName,
 * boolean showHat, int listOrder, RemoteChatSession.Data chatSession)}.
 */
public final class PacketSender {

    private static final EnumSet<Action> ADD_ACTIONS = EnumSet.of(
            Action.ADD_PLAYER,
            Action.UPDATE_LISTED,
            Action.UPDATE_LATENCY,
            Action.UPDATE_GAME_MODE,
            Action.UPDATE_DISPLAY_NAME,
            Action.UPDATE_LIST_ORDER,
            Action.UPDATE_HAT);

    /** Максимум записей в одном пакете, чтобы не собирать гигантские пакеты при тысячах фейков. */
    private static final int CHUNK = 256;

    private final Logger logger;
    private boolean errorLogged;
    private GameType gameType = GameType.SURVIVAL;
    private boolean showHat = true;

    public PacketSender(Logger logger) {
        this.logger = logger;
    }

    public void configure(GameMode mode, boolean showHat) {
        this.gameType = GameType.byId(mode.getValue());
        this.showHat = showHat;
    }

    public void sendAdd(Collection<? extends Player> receivers, List<FakeEntry> entries) {
        if (receivers.isEmpty() || entries.isEmpty()) {
            return;
        }
        for (int from = 0; from < entries.size(); from += CHUNK) {
            List<FakeEntry> part = entries.subList(from, Math.min(entries.size(), from + CHUNK));
            Packet<?> packet;
            try {
                packet = buildAdd(part);
            } catch (RuntimeException e) {
                logOnce("Не удалось собрать пакет PlayerInfoUpdate", e);
                return;
            }
            broadcast(receivers, packet);
        }
    }

    public void sendRemove(Collection<? extends Player> receivers, List<UUID> uuids) {
        if (receivers.isEmpty() || uuids.isEmpty()) {
            return;
        }
        for (int from = 0; from < uuids.size(); from += CHUNK) {
            List<UUID> part = new ArrayList<>(uuids.subList(from, Math.min(uuids.size(), from + CHUNK)));
            broadcast(receivers, new ClientboundPlayerInfoRemovePacket(part));
        }
    }

    private Packet<?> buildAdd(List<FakeEntry> part) {
        List<ClientboundPlayerInfoUpdatePacket.Entry> nms = new ArrayList<>(part.size());
        for (FakeEntry e : part) {
            nms.add(new ClientboundPlayerInfoUpdatePacket.Entry(
                    e.uuid(),
                    new GameProfile(e.uuid(), e.name()),
                    true,
                    e.latency(),
                    gameType,
                    PaperAdventure.asVanilla(e.displayName()),
                    showHat,
                    e.listOrder(),
                    null));
        }
        return new ClientboundPlayerInfoUpdatePacket(ADD_ACTIONS, nms);
    }

    private void broadcast(Collection<? extends Player> receivers, Packet<?> packet) {
        for (Player p : receivers) {
            try {
                ((CraftPlayer) p).getHandle().connection.send(packet);
            } catch (RuntimeException e) {
                logOnce("Не удалось отправить пакет игроку " + p.getName(), e);
            }
        }
    }

    private void logOnce(String message, Throwable t) {
        if (errorLogged) {
            return;
        }
        errorLogged = true;
        logger.log(Level.WARNING, message + " (дальнейшие ошибки пакетов не логируются)", t);
    }
}
