package com.rehard.securityclient;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fullscreen GUI for displaying interactive votes. Shows the title,
 * description, options as buttons and optional background image. Sends
 * selected option to the server and closes itself. Vote statistics are
 * updated live. When the vote ends (timeout or manual) the screen is
 * force-closed and final counts can be displayed briefly.
 */
public class VoteScreen extends Screen {
    private static final Map<String, VoteScreen> ACTIVE = new ConcurrentHashMap<>();
    private final String voteId;
    private final String titleText;
    private final String description;
    private final List<String> options;
    private final boolean freezeMovement;
    private final String imageData;
    private final int durationSec;
    private Identifier bgTex = null;
    private NativeImageBackedTexture bgTextureObj = null;
    private final List<ButtonWidget> buttons = new ArrayList<>();
    private int[] counts;

    public VoteScreen(String voteId, String title, String description, List<String> options,
                      boolean freezeMovement, String imageData, int durationSec) {
        super(Text.literal(title));
        this.voteId = voteId;
        this.titleText = title;
        this.description = description;
        this.options = options;
        this.freezeMovement = freezeMovement;
        this.imageData = imageData;
        this.durationSec = durationSec;
        this.counts = new int[options.size()];
        ACTIVE.put(voteId, this);
    }

    @Override
    protected void init() {
        int y = this.height / 4;
        int buttonWidth = 220;
        int buttonHeight = 20;
        int gap = 6;
        for (int i = 0; i < options.size(); i++) {
            final int idx = i;
            ButtonWidget btn = ButtonWidget.builder(Text.literal(options.get(i)), b -> {
                sendVoteResponse(idx);
                close();
            }).dimensions((this.width - buttonWidth) / 2, y + i * (buttonHeight + gap), buttonWidth, buttonHeight).build();
            addDrawableChild(btn);
            buttons.add(btn);
        }
        if (imageData != null && !imageData.isBlank()) {
            try {
                byte[] raw = java.util.Base64.getDecoder().decode(imageData);
                NativeImage img = NativeImage.read(new ByteArrayInputStream(raw));
                bgTextureObj = new NativeImageBackedTexture(img);
                /**bgTex = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("vote_bg_" + voteId, bgTextureObj);**/
                Identifier dynId = Identifier.of("securityclient", "vote_bg_" + voteId);
                MinecraftClient.getInstance().getTextureManager().registerTexture(dynId, bgTextureObj);
                bgTex = dynId;
            } catch (Exception ex) {
                bgTex = null;
            }
        }
    }

    @Override
    public void removed() {
        ACTIVE.remove(voteId);
        SecurityClientMod.unfreeze();
        if (bgTextureObj != null) {
            bgTextureObj.close();
            bgTextureObj = null;
        }
    }

    @Override
    public boolean shouldPause() { return true; }

    /**
     * Sends the selected option to the server.  Delegates to
     * {@link SecurityClientMod#sendVoteResponse(String, int)} so that the
     * vote response is encoded via the plugin-channel as expected by the
     * Paper server.
     */
    private void sendVoteResponse(int optionIndex) {
        // Use the public API on SecurityClientMod rather than constructing a legacy payload
        SecurityClientMod.sendVoteResponse(voteId, optionIndex);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        if (bgTex != null) {
            RenderSystem.enableBlend();
            // базовый GUI пайплайн, параметры: id, x, y, u, v, width, height, texW, texH
            context.drawTexture(
                RenderLayer::getGuiTextured,
                bgTex,
                0, 0,           // x, y
                0.0F, 0.0F,     // u, v
                this.width, this.height,
                this.width, this.height,
                (int)0          // z; в некоторых маппингах это может быть int/float — подберите по сигнатуре IDE
            );
            context.fill(0, 0, this.width, this.height, 0x66000000);
            RenderSystem.disableBlend();
        } else {
            context.fill(0, 0, this.width, this.height, 0xAA000000);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        // центрирование текста вручную
        net.minecraft.text.Text title = net.minecraft.text.Text.literal(titleText);
        int tw = this.textRenderer.getWidth(title);
        context.drawText(this.textRenderer, title, (this.width - tw) / 2, 20, 0xFFFFFFFF, false);

        net.minecraft.text.Text desc = net.minecraft.text.Text.literal(description);
        int dw = this.textRenderer.getWidth(desc);
        context.drawText(this.textRenderer, desc, (this.width - dw) / 2, 40, 0xFFDDDDDD, false);

        super.render(context, mouseX, mouseY, delta);
        
        for (int i = 0; i < buttons.size() && counts != null && i < counts.length; i++) {
            ButtonWidget b = buttons.get(i);
            String s = String.valueOf(counts[i]);
            int x = b.getX() + b.getWidth() + 6;
            int y = b.getY() + (b.getHeight() - 9) / 2;
            context.drawTexture(RenderLayer::getGuiTextured, bgTex, 0, 0, 0f, 0f, this.width, this.height, this.width, this.height);
        }
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(null);
    }

    /**
     * Updates vote statistics for the active screen. Called by the client mod
     * when VoteStats packets arrive from the server.
     */
    public static void updateStats(String voteId, int[] counts) {
        VoteScreen screen = ACTIVE.get(voteId);
        if (screen != null && counts.length == screen.counts.length) {
            screen.counts = Arrays.copyOf(counts, counts.length);
        }
    }

    /**
     * Forces a vote screen to close, optionally updating final counts. Used
     * when a VoteEnd packet is received.
     */
    public static void forceClose(String voteId, int[] counts) {
        VoteScreen screen = ACTIVE.get(voteId);
        if (screen != null) {
            if (counts != null && counts.length == screen.counts.length) {
                screen.counts = Arrays.copyOf(counts, counts.length);
            }
            screen.close();
        }
    }
}