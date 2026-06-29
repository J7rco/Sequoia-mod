package org.sequoia.seq.managers;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;

public final class PartyHealthBarRenderer {
    private static final int BAR_SEGMENTS = 20;
    private static final int NAME_TAG_LINE_HEIGHT = 10;
    private static final int EXTRA_EARS_Y_OFFSET = -10;

    private PartyHealthBarRenderer() {}

    public static void renderAboveNameTag(
            AvatarRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState cameraState,
            float percent) {
        if (state.nameTagAttachment == null) {
            return;
        }

        percent = Math.max(0f, Math.min(1f, percent));
        collector.submitNameTag(
                poseStack,
                state.nameTagAttachment,
                barYOffset(state),
                healthBarText(percent),
                !state.isDiscrete,
                state.lightCoords,
                state.distanceToCameraSq,
                cameraState);
    }

    private static int barYOffset(AvatarRenderState state) {
        int yOffset = state.showExtraEars ? EXTRA_EARS_Y_OFFSET : 0;
        int occupiedLines = 0;
        if (state.scoreText != null) {
            occupiedLines++;
        }
        if (state.nameTag != null) {
            occupiedLines++;
        }

        return yOffset - (Math.max(1, occupiedLines) * NAME_TAG_LINE_HEIGHT);
    }

    private static Component healthBarText(float percent) {
        int filledSegments = Math.round(BAR_SEGMENTS * percent);
        int emptySegments = BAR_SEGMENTS - filledSegments;

        return Component.empty()
                .append(Component.literal("["))
                .append(Component.literal("|".repeat(filledSegments)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("|".repeat(emptySegments)).withStyle(ChatFormatting.DARK_RED))
                .append(Component.literal("]"));
    }
}
