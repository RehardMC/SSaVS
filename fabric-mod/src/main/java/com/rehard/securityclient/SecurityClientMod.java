package com.rehard.securityclient;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import com.rehard.securityclient.net.RawPluginPayload;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Client-side implementation for the SSaVS mod.  Uses Bukkit-style
 * plugin messages (DataOutput/DataInput) instead of Fabric's custom
 * payloads to communicate with the Paper plugin on the server.
 *
 * Supported subchannels (sent by the server):
 *  - "RequestMods"  — server asks the client to send its mod/resource pack list
 *  - "StartVote"    — starts a voting UI on the client
 *  - "VoteStats"    — updates vote statistics
 *  - "VoteEnd"      — finalizes a vote and unfreezes the player if needed
 *  - "Pong"         — reply to a connectivity check (optional)
 *
 * The client proactively sends its mod/resource-pack list as soon as it
 * joins the server, so that the server can validate even if it never
 * sends a RequestMods packet.
 */
@Environment(EnvType.CLIENT)
public final class SecurityClientMod implements ClientModInitializer {

    /**
     * Plugin message channel used for all client/server communication. Must
     * exactly match the channel name used on the Paper plugin side.
     */
    /** Toggle for freezing/unfreezing player movement during votes. */
    private static volatile boolean freezeMovement = false;

    @Override
    public void onInitializeClient() {

        PayloadTypeRegistry.playS2C().register(RawPluginPayload.ID, RawPluginPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RawPluginPayload.ID, RawPluginPayload.CODEC);
        // Register a global receiver for plugin messages from the server.
        // Messages are encoded using DataOutput (UTF/int/boolean) and must
        // therefore be decoded with DataInput.
        ClientPlayNetworking.registerGlobalReceiver(RawPluginPayload.ID, (payload, ctx) -> {
            byte[] bytes = payload.data();
            ByteArrayDataInput in = ByteStreams.newDataInput(bytes);
            String sub = safeReadUTF(in);
            if (sub == null) return;
            switch (sub) {
                case "RequestMods" -> ctx.client().execute(() -> sendModList(ctx.client()));
                case "StartVote"   -> ctx.client().execute(() -> handleStartVote(in));
                case "VoteStats"   -> ctx.client().execute(() -> handleVoteStats(in));
                case "VoteEnd"     -> ctx.client().execute(() -> handleVoteEnd(in));
                case "Pong"        -> logClient("Received Pong from server");
                default -> logClient("Unknown subchannel: " + sub);
            }
        });

        // Proactively send mod/resource pack list upon joining a server.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            sendModList(client);
            // additional send on next tick to catch resource-pack manager initialisation
            client.execute(() -> sendModList(client));
        });

        // Freeze player movement every tick when freezeMovement is active.
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (!freezeMovement) return;
            ClientPlayerEntity player = mc.player;
            if (player != null) {
                player.setVelocity(0, player.getVelocity().y, 0);
                player.setSprinting(false);
            }
        });

        logClient("SecurityClientMod initialised");
    }

    // -------------------------------------------------------------------------
    // Outgoing messages (client -> server)
    // -------------------------------------------------------------------------

    /**
     * Sends a vote response.  Exposed as static so VoteScreen can call it.
     *
     * @param voteId      the vote identifier from the server
     * @param optionIndex which option the player selected
     */
    public static void sendVoteResponse(String voteId, int optionIndex) {
        try {
            var out = ByteStreams.newDataOutput();
            out.writeUTF("VoteResponse");
            out.writeUTF(voteId);
            out.writeInt(optionIndex);
            ClientPlayNetworking.send(new RawPluginPayload(out.toByteArray()));
            logClient("Sent VoteResponse for " + voteId + " option=" + optionIndex);
        } catch (Exception e) {
            logClient("Failed to send VoteResponse: " + e.getMessage());
        }
    }

    /**
     * Collects the list of installed mods and enabled resource packs and
     * sends them to the server.  Called both on JOIN and when the
     * server explicitly requests the list via "RequestMods".
     */
    private static void sendModList(MinecraftClient client) {
        try {
            // Gather mod IDs from FabricLoader
            Collection<ModContainer> mods = FabricLoader.getInstance().getAllMods();
            List<String> modIds = new ArrayList<>(mods.size());
            for (ModContainer c : mods) modIds.add(c.getMetadata().getId());

            // Gather resource pack names from the client's manager
            List<String> packs = new ArrayList<>();
            try {
                var rpm = client.getResourcePackManager();
                for (ResourcePackProfile p : rpm.getEnabledProfiles()) packs.add(p.getDisplayName().getString());
            } catch (Throwable ignore) {
                // Resource pack manager may not be initialised yet; skip for now.
            }

            // Encode into raw bytes using DataOutput in the exact order expected by the server
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("ModList");
            out.writeInt(modIds.size());
            for (String id : modIds) out.writeUTF(id);
            out.writeInt(packs.size());
            for (String pack : packs) out.writeUTF(pack);

            ClientPlayNetworking.send(new RawPluginPayload(out.toByteArray()));

            logClient("Sent ModList: " + modIds.size() + " mods, " + packs.size() + " packs");
        } catch (Exception e) {
            logClient("Failed to send ModList: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Incoming message handlers (server -> client)
    // -------------------------------------------------------------------------

    /**
     * Handles the StartVote packet.  Reads vote data from the input and opens
     * the VoteScreen.  Freezes player movement if movement is not allowed.
     */
    private static void handleStartVote(ByteArrayDataInput in) {
        try {
            String voteId = in.readUTF();
            String title = in.readUTF();
            String desc = in.readUTF();
            int optionCount = in.readInt();
            if (optionCount < 0 || optionCount > 10_000) return;
            List<String> options = new ArrayList<>(optionCount);
            for (int i = 0; i < optionCount; i++) options.add(in.readUTF());
            boolean allowMove = in.readBoolean();
            boolean hasImage = in.readBoolean();
            String imageData = null;
            if (hasImage) imageData = in.readUTF();
            int duration = in.readInt();
            // Freeze movement if not allowed
            if (!allowMove) freezeMovement();
            MinecraftClient.getInstance().setScreen(
                new VoteScreen(voteId, title, desc, options, !allowMove, imageData, duration)
            );
        } catch (Exception e) {
            logClient("Failed to handle StartVote: " + e.getMessage());
        }
    }

    /**
     * Handles VoteStats by updating counts on the active VoteScreen.
     */
    private static void handleVoteStats(ByteArrayDataInput in) {
        try {
            String voteId = in.readUTF();
            int n = in.readInt();
            if (n < 0 || n > 10_000) return;
            int[] counts = new int[n];
            for (int i = 0; i < n; i++) counts[i] = in.readInt();
            VoteScreen.updateStats(voteId, counts);
        } catch (Exception e) {
            logClient("Failed to handle VoteStats: " + e.getMessage());
        }
    }

    /**
     * Handles VoteEnd, finalising a vote, unfreezing movement and showing a toast.
     */
    private static void handleVoteEnd(ByteArrayDataInput in) {
        try {
            String voteId = in.readUTF();
            String reason = in.readUTF();
            int n = in.readInt();
            if (n < 0 || n > 10_000) n = 0;
            int[] counts = new int[n];
            for (int i = 0; i < n; i++) counts[i] = in.readInt();
            VoteScreen.forceClose(voteId, counts);
            unfreeze();
            var mc = MinecraftClient.getInstance();
            SystemToast.add(
                mc.getToastManager(),
                SystemToast.Type.PERIODIC_NOTIFICATION,
                Text.literal("Голосование завершено"),
                Text.literal(reason == null ? "" : reason)
            );
        } catch (Exception e) {
            logClient("Failed to handle VoteEnd: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Utility methods
    // -------------------------------------------------------------------------

    /**
     * Safely read a UTF string from the input.  Returns null on failure.
     */
    private static String safeReadUTF(ByteArrayDataInput in) {
        try {
            return in.readUTF();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Freeze/unfreeze player movement.  Called when vote UIs are opened/closed.
     */
    public static void freezeMovement() { freezeMovement = true; }
    public static void unfreeze()      { freezeMovement = false; }

    /**
     * Simple logger that prints to console and optionally shows a toast for
     * easier debugging during development.  Toasts are abbreviated to 60
     * characters.
     */
    private static void logClient(String msg) {
        System.out.println("[SecurityClient] " + msg);
        try {
            var mc = MinecraftClient.getInstance();
            if (mc != null && mc.getToastManager() != null) {
                SystemToast.add(
                    mc.getToastManager(),
                    SystemToast.Type.PERIODIC_NOTIFICATION,
                    Text.literal("Security"),
                    Text.literal(msg.length() > 60 ? msg.substring(0, 60) + "..." : msg)
                );
            }
        } catch (Throwable ignore) {
            // If toast cannot be displayed, do nothing; console log is sufficient
        }
    }
}