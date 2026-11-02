package com.example.ssr;

import org.junit.Test;

import static org.junit.Assert.*;

public class CropMathTest {

    @Test
    public void testEncoderDimensionsMultipleOf16() {
        int width = 715;
        int height = 1599;

        int encWidth = width - (width % 16);
        int encHeight = height - (height % 16);

        assertEquals(0, encWidth % 16);
        assertEquals(0, encHeight % 16);
        assertEquals(704, encWidth);
        assertEquals(1584, encHeight);
    }

    @Test
    public void testCropBoundsClampingWithinScreen() {
        int screenWidth = 720;
        int screenHeight = 1600;

        int cropLeft = 100;
        int cropTop = 200;
        int cropRight = 600;
        int cropBottom = 800;

        int cropW = cropRight - cropLeft;
        int cropH = cropBottom - cropTop;

        int encWidth = cropW - (cropW % 16);
        int encHeight = cropH - (cropH % 16);

        if (cropLeft + encWidth > screenWidth) {
            cropLeft = Math.max(0, screenWidth - encWidth);
        }
        cropRight = cropLeft + encWidth;

        if (cropTop + encHeight > screenHeight) {
            cropTop = Math.max(0, screenHeight - encHeight);
        }
        cropBottom = cropTop + encHeight;

        assertTrue(cropLeft >= 0);
        assertTrue(cropRight <= screenWidth);
        assertTrue(cropTop >= 0);
        assertTrue(cropBottom <= screenHeight);
        assertEquals(encWidth, cropRight - cropLeft);
        assertEquals(encHeight, cropBottom - cropTop);
        assertEquals(0, (cropRight - cropLeft) % 16);
        assertEquals(0, (cropBottom - cropTop) % 16);
    }

    @Test
    public void testCropTextureCoordinatesMapping() {
        int screenWidth = 720;
        int screenHeight = 1600;

        int rectLeft = 72;
        int rectTop = 160;
        int rectRight = 648;
        int rectBottom = 1440;

        float cropLeft = (float) rectLeft / screenWidth;
        float cropRight = (float) rectRight / screenWidth;
        float cropTop = (float) rectTop / screenHeight;
        float cropBottom = (float) rectBottom / screenHeight;

        assertEquals(0.1f, cropLeft, 0.001f);
        assertEquals(0.9f, cropRight, 0.001f);
        assertEquals(0.1f, cropTop, 0.001f);
        assertEquals(0.9f, cropBottom, 0.001f);

        // In OpenGL texture space where v=0 is bottom and v=1 is top:
        // Top of crop in texture space: 1.0f - cropTop = 0.9f
        // Bottom of crop in texture space: 1.0f - cropBottom = 0.1f
        float texTop = 1.0f - cropTop;
        float texBottom = 1.0f - cropBottom;

        assertEquals(0.9f, texTop, 0.001f);
        assertEquals(0.1f, texBottom, 0.001f);

        // Linear mapping from V in [0, 1] (output quad) to [texBottom, texTop]:
        // v' = texBottom + V * (texTop - texBottom)
        // At V = 0 (bottom of output quad):
        float vAtBottom = texBottom + 0.0f * (texTop - texBottom);
        assertEquals(texBottom, vAtBottom, 0.001f);

        // At V = 1 (top of output quad):
        float vAtTop = texBottom + 1.0f * (texTop - texBottom);
        assertEquals(texTop, vAtTop, 0.001f);

        // Crucial: top of quad maps to top of crop, bottom of quad maps to bottom of crop
        assertTrue("Top of quad must sample higher texture V than bottom of quad", vAtTop > vAtBottom);
    }

    @Test
    public void testAudioPacingFrameDuration() {
        int sampleRate = 44100;
        int samplesPerFrame = 1024;
        long frameDurationUs = (1000000L * samplesPerFrame) / sampleRate;

        // 1024 / 44100 s = ~23219 us = ~23.2 ms
        assertEquals(23219L, frameDurationUs);

        // After 43 frames (~1 second of audio):
        long totalUs = frameDurationUs * 43;
        assertEquals(998417L, totalUs); // ~0.998s, within 1.5ms of 1s
    }

    @Test
    public void testFloatingControlClampingOutsideCropRect() {
        int screenWidth = 720;
        int screenHeight = 1600;
        int windowWidth = 200;
        int windowHeight = 60;

        // Crop rectangle in center of screen
        int cropLeft = 100, cropTop = 300, cropRight = 600, cropBottom = 1000;

        // Control placed inside crop rect
        int posX = 200;
        int posY = 500;
        
        boolean initialIntersects = intersects(posX, posY, posX + windowWidth, posY + windowHeight,
                cropLeft, cropTop, cropRight, cropBottom);
        assertTrue("Initially control should intersect crop rect", initialIntersects);

        // Clamp outside logic from FloatingRecorderControl:
        boolean canDown = cropBottom + windowHeight + 16 <= screenHeight;
        boolean canUp = cropTop - windowHeight - 16 >= 0;
        boolean canRight = cropRight + windowWidth + 16 <= screenWidth;
        boolean canLeft = cropLeft - windowWidth - 16 >= 0;

        if (canDown) {
            posY = cropBottom + 16;
        } else if (canUp) {
            posY = cropTop - windowHeight - 16;
        } else if (canRight) {
            posX = cropRight + 16;
        } else if (canLeft) {
            posX = cropLeft - windowWidth - 16;
        }

        // After clamping, must NOT intersect with cropRect!
        boolean afterIntersects = intersects(posX, posY, posX + windowWidth, posY + windowHeight,
                cropLeft, cropTop, cropRight, cropBottom);
        assertFalse("Control must not overlap recorded crop rectangle", afterIntersects);
    }

    private static boolean intersects(int l1, int t1, int r1, int b1, int l2, int t2, int r2, int b2) {
        return l1 < r2 && r1 > l2 && t1 < b2 && b1 > t2;
    }

    @Test
    public void testLandscapeDimensionsAndCropAlignment() {
        int landWidth = 1600;
        int landHeight = 720;

        // Ensure 16-pixel alignment for landscape screen recording
        int encWidth = landWidth - (landWidth % 16);
        int encHeight = landHeight - (landHeight % 16);
        assertEquals(1600, encWidth);
        assertEquals(720, encHeight);

        // Partial crop in landscape
        int cropLeft = 250;
        int cropTop = 80;
        int cropRight = 1350;
        int cropBottom = 640;

        int cropW = cropRight - cropLeft;
        int cropH = cropBottom - cropTop;

        int encCropW = cropW - (cropW % 16);
        int encCropH = cropH - (cropH % 16);

        assertEquals(0, encCropW % 16);
        assertEquals(0, encCropH % 16);
        assertTrue(cropLeft + encCropW <= landWidth);
        assertTrue(cropTop + encCropH <= landHeight);
    }
}
