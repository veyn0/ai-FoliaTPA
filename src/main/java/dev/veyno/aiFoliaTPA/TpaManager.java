package dev.veyno.aiFoliaTPA;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class TpaManager implements Listener {
    private final AiFoliaTPA plugin;
    private final MessageService messages;
    private final Map<UUID, Map<UUID, TpaRequest>> requestsByTarget = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastRequestTime = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastTeleportTime = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveTeleport> activeTeleports = new ConcurrentHashMap<>();
    private final int requestCooldownSeconds;
    private final int postTeleportCooldownSeconds;
    private final int countdownSeconds;

    public TpaManager(AiFoliaTPA plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.requestCooldownSeconds = messages.getInt("settings.request-cooldown-seconds", 20);
        this.postTeleportCooldownSeconds = messages.getInt("settings.post-teleport-cooldown-seconds", 60);
        this.countdownSeconds = messages.getInt("settings.countdown-seconds", 3);
    }

    public void sendConsoleOnly(CommandSender sender) {
        messages.send(sender, "only-players");
    }

    public void sendUsage(Player player, String usage) {
        messages.send(player, "usage", Map.of("usage", usage));
    }

    public void sendRequest(Player requester, String targetName, RequestType type) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            messages.send(requester, "request-offline", Map.of("target", targetName));
            return;
        }
        if (requester.getUniqueId().equals(target.getUniqueId())) {
            messages.send(requester, "request-self");
            return;
        }
        long remainingCooldown = remainingCooldownSeconds(requester.getUniqueId());
        if (remainingCooldown > 0) {
            messages.send(requester, "request-cooldown", Map.of("seconds", String.valueOf(remainingCooldown)));
            return;
        }
        Map<UUID, TpaRequest> requests = requestsByTarget.computeIfAbsent(target.getUniqueId(), unused -> new ConcurrentHashMap<>());
        if (requests.containsKey(requester.getUniqueId())) {
            messages.send(requester, "request-already-pending", Map.of("target", target.getName()));
            return;
        }
        TpaRequest request = new TpaRequest(requester.getUniqueId(), target.getUniqueId(), type, Instant.now());
        requests.put(requester.getUniqueId(), request);
        lastRequestTime.put(requester.getUniqueId(), Instant.now());

        messages.send(requester, "request-sent", Map.of("target", target.getName()));
        target.getScheduler().run(plugin,
            task -> messages.send(target, type == RequestType.TPA ? "request-received" : "request-received-here",
                Map.of("requester", requester.getName())),
            () -> {
            });
    }

    public void acceptRequest(Player target, String requesterName, RequestType type) {
        Player requester = Bukkit.getPlayerExact(requesterName);
        if (requester == null) {
            messages.send(target, "accept-offline", Map.of("requester", requesterName));
            return;
        }
        Map<UUID, TpaRequest> requests = requestsByTarget.getOrDefault(target.getUniqueId(), Map.of());
        TpaRequest request = requests.get(requester.getUniqueId());
        if (request == null || request.type() != type) {
            messages.send(target, "accept-no-request", Map.of("requester", requester.getName()));
            return;
        }
        requests.remove(requester.getUniqueId());
        if (requests.isEmpty()) {
            requestsByTarget.remove(target.getUniqueId());
        }

        messages.send(target, "accept-success", Map.of("requester", requester.getName()));
        requester.getScheduler().run(plugin,
            task -> messages.send(requester, "accept-notify", Map.of("target", target.getName())),
            () -> {
            });

        Player teleporting = type == RequestType.TPA ? requester : target;
        Player destination = type == RequestType.TPA ? target : requester;
        startTeleportCountdown(teleporting, destination, request.requester());
    }

    public List<String> getPendingRequesters(Player target, RequestType type) {
        Map<UUID, TpaRequest> requests = requestsByTarget.getOrDefault(target.getUniqueId(), Map.of());
        List<String> names = new ArrayList<>();
        for (TpaRequest request : requests.values()) {
            if (request.type() != type) {
                continue;
            }
            Player requester = Bukkit.getPlayer(request.requester());
            if (requester != null && requester.isOnline()) {
                names.add(requester.getName());
            }
        }
        return names;
    }

    public void shutdown() {
        for (ActiveTeleport active : activeTeleports.values()) {
            active.task().cancel();
        }
        activeTeleports.clear();
        requestsByTarget.clear();
    }

    private void startTeleportCountdown(Player teleporting, Player destination, UUID requesterId) {
        if (activeTeleports.containsKey(teleporting.getUniqueId())) {
            messages.send(teleporting, "teleport-already");
            return;
        }
        if (teleporting.isDead() || !teleporting.isOnline() || destination.isDead() || !destination.isOnline()) {
            messages.send(teleporting, "teleport-unavailable");
            return;
        }
        Location start = teleporting.getLocation().clone();
        AtomicInteger ticksRemaining = new AtomicInteger(countdownSeconds * 20);
        ScheduledTask task = teleporting.getScheduler().runAtFixedRate(plugin, scheduledTask -> {
            if (!teleporting.isOnline() || teleporting.isDead()) {
                cancelTeleport(teleporting.getUniqueId(), CancelReason.DEAD);
                return;
            }
            if (!destination.isOnline() || destination.isDead()) {
                cancelTeleport(teleporting.getUniqueId(), CancelReason.OFFLINE);
                return;
            }
            if (hasMoved(start, teleporting.getLocation())) {
                cancelTeleport(teleporting.getUniqueId(), CancelReason.MOVED);
                return;
            }
            int remaining = ticksRemaining.getAndDecrement();
            if (remaining <= 0) {
                scheduledTask.cancel();
                activeTeleports.remove(teleporting.getUniqueId());
                executeTeleport(teleporting, destination, requesterId);
                return;
            }
            if (remaining % 20 == 0) {
                teleporting.playSound(teleporting.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
                messages.send(teleporting, "teleport-countdown", Map.of("seconds", String.valueOf(remaining / 20)));
            }
        }, () -> {
        }, 1L, 1L);
        activeTeleports.put(teleporting.getUniqueId(), new ActiveTeleport(task, destination.getUniqueId()));
        messages.send(teleporting, "teleport-start");
    }

    private void executeTeleport(Player teleporting, Player destination, UUID requesterId) {
        destination.getScheduler().run(plugin, task -> {
            if (!destination.isOnline() || destination.isDead()) {
                cancelTeleport(teleporting.getUniqueId(), CancelReason.OFFLINE);
                return;
            }
            Location destinationLocation = destination.getLocation().clone();
            teleporting.getScheduler().run(plugin, teleportTask -> {
                if (!teleporting.isOnline() || teleporting.isDead()) {
                    cancelTeleport(teleporting.getUniqueId(), CancelReason.DEAD);
                    return;
                }
                teleporting.teleportAsync(destinationLocation).thenRun(() -> {
                    lastTeleportTime.put(requesterId, Instant.now());
                    messages.send(teleporting, "teleport-success", Map.of("target", destination.getName()));
                    destination.getScheduler().run(plugin, notifyTask -> messages.send(destination, "teleport-success-other",
                        Map.of("player", teleporting.getName())), () -> {
                    });
                });
            }, () -> {
            });
        }, () -> {
        });
    }

    private void cancelTeleport(UUID teleportingId, CancelReason reason) {
        ActiveTeleport active = activeTeleports.remove(teleportingId);
        if (active != null) {
            active.task().cancel();
            Player teleporting = Bukkit.getPlayer(teleportingId);
            Player other = Bukkit.getPlayer(active.otherPlayer());
            if (teleporting != null) {
                String messageKey = reason == CancelReason.MOVED ? "teleport-cancel-move"
                    : reason == CancelReason.DEAD ? "teleport-cancel-dead"
                    : "teleport-cancel-offline";
                messages.send(teleporting, messageKey);
            }
            if (other != null) {
                messages.send(other, "teleport-cancel-other", Map.of("player", teleporting != null ? teleporting.getName() : "Unbekannt"));
            }
        }
    }

    private boolean hasMoved(Location start, Location current) {
        if (!Objects.equals(start.getWorld(), current.getWorld())) {
            return true;
        }
        double dx = start.getX() - current.getX();
        double dy = start.getY() - current.getY();
        double dz = start.getZ() - current.getZ();
        return (dx * dx + dy * dy + dz * dz) > 0.0001D;
    }

    private long remainingCooldownSeconds(UUID requesterId) {
        Instant now = Instant.now();
        Instant lastRequest = lastRequestTime.get(requesterId);
        Instant lastTeleport = lastTeleportTime.get(requesterId);
        long requestRemaining = lastRequest == null ? 0 : requestCooldownSeconds - Duration.between(lastRequest, now).getSeconds();
        long teleportRemaining = lastTeleport == null ? 0 : postTeleportCooldownSeconds - Duration.between(lastTeleport, now).getSeconds();
        return Math.max(0, Math.max(requestRemaining, teleportRemaining));
    }

    private void removeRequestsFor(UUID playerId) {
        requestsByTarget.remove(playerId);
        for (Map<UUID, TpaRequest> requests : requestsByTarget.values()) {
            requests.remove(playerId);
        }
    }

    private void cancelActiveTeleportsFor(UUID playerId, CancelReason reason) {
        List<UUID> toCancel = new ArrayList<>();
        for (Map.Entry<UUID, ActiveTeleport> entry : activeTeleports.entrySet()) {
            if (entry.getKey().equals(playerId) || entry.getValue().otherPlayer().equals(playerId)) {
                toCancel.add(entry.getKey());
            }
        }
        for (UUID teleportingId : toCancel) {
            cancelTeleport(teleportingId, reason);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        handlePlayerUnavailable(event.getPlayer(), CancelReason.OFFLINE);
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        handlePlayerUnavailable(event.getPlayer(), CancelReason.OFFLINE);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        handlePlayerUnavailable(event.getEntity(), CancelReason.DEAD);
    }

    private void handlePlayerUnavailable(Player player, CancelReason reason) {
        removeRequestsFor(player.getUniqueId());
        cancelActiveTeleportsFor(player.getUniqueId(), reason);
    }

    private enum CancelReason {
        MOVED,
        OFFLINE,
        DEAD
    }

    private record ActiveTeleport(ScheduledTask task, UUID otherPlayer) {
    }
}
