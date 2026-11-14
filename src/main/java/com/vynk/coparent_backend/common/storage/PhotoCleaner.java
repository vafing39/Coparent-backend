package com.vynk.coparent_backend.common.storage;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.vynk.coparent_backend.common.ApiException;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

/**
 * Nettoie une photo envoyée (justificatif de dépense, document) avant de la stocker : JPEG ou PNG seulement (reconnus par leur contenu), orientation
 * EXIF appliquée, 2000 px au plus sur le grand côté, puis réencodage en JPEG. Le fichier produit ne contient aucune
 * métadonnée (position GPS, appareil, date), quoi que l'app ait envoyé.
 */
@Component
public class PhotoCleaner {

    static final int MAX_SIDE = 2000;
    /** La taille du fichier est vérifiée par l'appelant. Au-delà, l'image n'est pas décodée (protection contre les images démesurées). */
    static final long MAX_PIXELS = 60_000_000L;
    public static final String INVALID = "Le fichier doit être une photo (JPEG ou PNG).";
    private static final float QUALITY = 0.85f;

    public byte[] clean(byte[] input) {
        if (input == null || input.length == 0) {
            throw ApiException.badRequest(INVALID);
        }
        boolean jpeg = isJpeg(input);
        if (!jpeg && !isPng(input)) {
            throw ApiException.badRequest(INVALID);
        }
        BufferedImage image = decode(input);
        if (jpeg) {
            image = orient(image, orientation(input));
        }
        return encodeJpeg(flattenAndResize(image));
    }

    public static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    public static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static BufferedImage decode(byte[] input) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw ApiException.badRequest(INVALID);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw ApiException.badRequest("Cette photo est trop grande.");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException apiException) {
                throw apiException;
            }
            throw ApiException.badRequest(INVALID);
        }
    }

    /** Orientation EXIF (1 à 8), 1 si absente ou illisible. */
    static int orientation(byte[] input) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(input));
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (ImageProcessingException | IOException | MetadataException e) {
            // Métadonnées illisibles : l'image est gardée telle quelle.
        }
        return 1;
    }

    /** Redresse l'image selon l'orientation EXIF (miroirs et quarts de tour). */
    static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(image, t, null);
        g.dispose();
        return out;
    }

    /** Fond blanc (PNG transparent), RVB, 2000 px au plus sur le grand côté. */
    static BufferedImage flattenAndResize(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, tw, th);
        g.drawImage(image, 0, 0, tw, th, null);
        g.dispose();
        return out;
    }

    /** JPEG sans métadonnées : l'encodeur d'ImageIO n'écrit que l'en-tête JFIF. */
    private static byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
