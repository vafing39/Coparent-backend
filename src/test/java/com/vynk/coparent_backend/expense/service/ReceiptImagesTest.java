package com.vynk.coparent_backend.expense.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import com.vynk.coparent_backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ReceiptImagesTest {

    private final ReceiptImages images = new ReceiptImages();

    @Test
    void gpsAndExifAreRemovedAndOrientationIsApplied() throws Exception {
        // 40 × 20 : moitié gauche rouge, droite bleue ; orientation 6 = un quart de tour horaire à l'affichage
        byte[] input = withExif(jpeg(halves(40, 20)), 6);
        Metadata before = ImageMetadataReader.readMetadata(new ByteArrayInputStream(input));
        assertThat(before.getFirstDirectoryOfType(GpsDirectory.class)).isNotNull();
        assertThat(ReceiptImages.orientation(input)).isEqualTo(6);

        byte[] output = images.clean(input);

        Metadata after = ImageMetadataReader.readMetadata(new ByteArrayInputStream(output));
        assertThat(after.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(after.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();
        assertThat(new String(output, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(output));
        assertThat(image.getWidth()).isEqualTo(20);
        assertThat(image.getHeight()).isEqualTo(40);
        // le côté gauche (rouge) passe en haut
        assertThat(isRed(image.getRGB(10, 5))).isTrue();
        assertThat(isRed(image.getRGB(10, 35))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void everyExifOrientationIsStraightened(int orientation) {
        BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        int[] colors = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x00FFFF, 0xFF00FF};
        for (int i = 0; i < 6; i++) {
            source.setRGB(i % 3, i / 3, colors[i]);
        }

        BufferedImage out = ReceiptImages.orient(source, orientation);

        int w = 3;
        int h = 2;
        boolean swap = orientation >= 5;
        assertThat(out.getWidth()).isEqualTo(swap ? h : w);
        assertThat(out.getHeight()).isEqualTo(swap ? w : h);
        for (int y = 0; y < out.getHeight(); y++) {
            for (int x = 0; x < out.getWidth(); x++) {
                int[] from = switch (orientation) {
                    case 2 -> new int[]{w - 1 - x, y};
                    case 3 -> new int[]{w - 1 - x, h - 1 - y};
                    case 4 -> new int[]{x, h - 1 - y};
                    case 5 -> new int[]{y, x};
                    case 6 -> new int[]{y, h - 1 - x};
                    case 7 -> new int[]{w - 1 - y, h - 1 - x};
                    case 8 -> new int[]{w - 1 - y, x};
                    default -> new int[]{x, y};
                };
                assertThat(out.getRGB(x, y) & 0xFFFFFF)
                        .as("orientation %d, pixel (%d, %d)", orientation, x, y)
                        .isEqualTo(source.getRGB(from[0], from[1]) & 0xFFFFFF);
            }
        }
    }

    @Test
    void largePngIsShrunkFlattenedOnWhiteAndReencodedAsJpeg() throws Exception {
        BufferedImage transparent = new BufferedImage(3000, 1000, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(transparent, "png", png);

        byte[] output = images.clean(png.toByteArray());

        assertThat(ReceiptImages.isJpeg(output)).isTrue();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(output));
        assertThat(image.getWidth()).isEqualTo(2000);
        assertThat(image.getHeight()).isEqualTo(667);
        Color pixel = new Color(image.getRGB(1000, 300));
        assertThat(pixel.getRed()).isGreaterThan(245);
        assertThat(pixel.getBlue()).isGreaterThan(245);
    }

    @Test
    void anythingButAJpegOrPngIsRefused() throws Exception {
        assertThatThrownBy(() -> images.clean("%PDF-1.7 facture".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class)
                .hasMessage(ReceiptImages.INVALID);
        assertThatThrownBy(() -> images.clean(new byte[0])).isInstanceOf(ApiException.class);
        // un faux JPEG (bonne signature, contenu illisible)
        assertThatThrownBy(() -> images.clean(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3}))
                .isInstanceOf(ApiException.class);
        ByteArrayOutputStream gif = new ByteArrayOutputStream();
        ImageIO.write(halves(4, 4), "gif", gif);
        assertThatThrownBy(() -> images.clean(gif.toByteArray())).isInstanceOf(ApiException.class);
    }

    private static boolean isRed(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() > 200 && c.getBlue() < 60;
    }

    private static BufferedImage halves(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, x < width / 2 ? 0xFF0000 : 0x0000FF);
            }
        }
        return image;
    }

    public static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /**
     * Ajoute juste après le marqueur SOI un segment APP1 Exif : orientation et une position GPS (latitude N 48°),
     * comme une photo de téléphone.
     */
    public static byte[] withExif(byte[] jpeg, int orientation) {
        // TIFF big-endian : en-tête (8) + IFD0 (2 + 2 × 12 + 4 = 30) + IFD GPS (2 + 2 × 12 + 4 = 30) + rationnels (24)
        ByteBuffer tiff = ByteBuffer.allocate(8 + 30 + 30 + 24);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        int gpsOffset = 8 + 30;
        int latitudeOffset = gpsOffset + 30;
        tiff.putShort((short) 2);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsOffset);
        tiff.putInt(0);
        tiff.putShort((short) 2);
        tiff.putShort((short) 0x0001).putShort((short) 2).putInt(2).put((byte) 'N').put((byte) 0).putShort((short) 0);
        tiff.putShort((short) 0x0002).putShort((short) 5).putInt(3).putInt(latitudeOffset);
        tiff.putInt(0);
        tiff.putInt(48).putInt(1).putInt(51).putInt(1).putInt(24).putInt(1);

        byte[] header = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
        int length = 2 + header.length + tiff.capacity();
        ByteBuffer out = ByteBuffer.allocate(jpeg.length + 2 + length);
        out.put(jpeg, 0, 2);
        out.put((byte) 0xFF).put((byte) 0xE1).putShort((short) length);
        out.put(header).put(tiff.array());
        out.put(jpeg, 2, jpeg.length - 2);
        return out.array();
    }
}
